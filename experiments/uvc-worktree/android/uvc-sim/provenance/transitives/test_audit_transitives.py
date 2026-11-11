"""Parser/integrity checks use synthetic metadata and ZIPs; no network or upstream build."""
import unittest
import json
import pathlib
import tempfile
import contextlib
import io
import zipfile
from unittest import mock
import audit_transitives as audit_module
from audit_transitives import Audit, exact_version, parse_xml


def pom(body):
    return ("<project xmlns='http://maven.apache.org/POM/4.0.0'><modelVersion>4.0.0</modelVersion>"+body+"</project>").encode()


def dependency(version="", extra=""):
    return "<dependency><groupId>g</groupId><artifactId>dep</artifactId>"+("<version>"+version+"</version>" if version else "")+extra+"</dependency>"


class MemoryFetch:
    def __init__(self, values): self.values=values
    def artifact(self, gav, extension): return self.values.get(gav), "https://example.invalid/"+gav


class ParserChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        audit_module.CACHE.mkdir(parents=True, exist_ok=True)

    def test_license_and_property_inheritance(self):
        values={
            "g:parent:1": pom("<properties><dep.version>1</dep.version></properties><licenses><license><name>ParentLicense</name></license></licenses><dependencies>"+dependency("${dep.version}")+"</dependencies>"),
            "g:child:1": pom("<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent><properties><dep.version>2</dep.version></properties>")}
        model=Audit(MemoryFetch(values)).model("g:child:1")
        self.assertEqual(model["licenses"][0]["declared_in"],"g:parent:1")
        self.assertEqual(model["dependencies"][0]["target"],"g:dep:2")

    def test_bom_over_parent_and_local_over_bom(self):
        values={
            "g:parent:1": pom("<dependencyManagement><dependencies>"+dependency("1")+"</dependencies></dependencyManagement>"),
            "g:bom:1": pom("<dependencyManagement><dependencies>"+dependency("2")+"</dependencies></dependencyManagement>")}
        base="<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency>"
        for local, expected in (("","2"),(dependency("3"),"3")):
            values["g:child:1"]=pom(base+local+"</dependencies></dependencyManagement><dependencies>"+dependency()+"</dependencies>")
            model=Audit(MemoryFetch(values)).model("g:child:1")
            self.assertEqual(model["dependencies"][0]["version"],expected)
            self.assertEqual(model["bom_imports"],["g:bom:1"])

    def test_scope_optional_exclusion_and_singleton(self):
        extras="<optional>true</optional><exclusions><exclusion><groupId>x</groupId><artifactId>y</artifactId></exclusion></exclusions>"
        m=Audit(MemoryFetch({"g:c:1":pom("<dependencies>"+dependency("[1.2]",extras)+dependency("1","<type>test-jar</type><scope>test</scope>")+"</dependencies>")})).model("g:c:1")
        self.assertEqual(m["dependencies"][0]["target"],"g:dep:1.2")
        self.assertEqual(m["dependencies"][0]["optional"],"true")
        self.assertTrue(m["dependencies"][0]["followed"])
        self.assertFalse(m["dependencies"][1]["followed"])
        self.assertEqual(m["dependencies"][0]["exclusions"],[{"groupId":"x","artifactId":"y"}])

    def test_dynamic_ranges_remain_unresolved(self):
        for version in ("[1,2)","[1,)","LATEST","RELEASE","${v}","1.+","1-SNAPSHOT",""):
            self.assertIsNone(exact_version(version)[0],version)
        self.assertEqual(exact_version("1.0-RC1")[0],"1.0-RC1")

    def test_profiles_recorded_not_activated(self):
        m=Audit(MemoryFetch({"g:c:1":pom("<profiles><profile><id>platform</id><activation><activeByDefault>true</activeByDefault></activation><dependencies>"+dependency("4")+"</dependencies></profile></profiles>")})).model("g:c:1")
        self.assertEqual(m["dependencies"],[])
        self.assertEqual(m["profiles_not_activated"][0]["id"],"platform")

    def test_entities_rejected(self):
        with self.assertRaises(ValueError): parse_xml(b"<!DOCTYPE project><project/>")

    def test_offline_missing_cache_is_unknown_not_404(self):
        audit_module.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=audit_module.CACHE) as tmp:
            with mock.patch.object(audit_module,"CACHE",pathlib.Path(tmp)):
                fetch=audit_module.Fetcher(False)
                result=audit_module.module_summary(fetch,"g:a:1")
                self.assertFalse(result["present"])
                self.assertEqual(result["availability"],"unknown")
                self.assertTrue(all(r["status"]=="not_cached" for r in result["attempts"]))

    def test_confirmed_404_is_distinct_from_transport_error(self):
        fetch=audit_module.Fetcher(False)
        urls=fetch.urls("g:a:1","module")
        fetch.records={url:{"url":url,"status":404} for url in urls}
        self.assertEqual(fetch.absence("g:a:1","module")["availability"],"confirmed_absent_404")
        fetch.records[urls[0]]["status"]="transport_error"
        self.assertEqual(fetch.absence("g:a:1","module")["availability"],"unknown")

    def test_cache_tampering_is_rejected(self):
        audit_module.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=audit_module.CACHE) as tmp:
            root=pathlib.Path(tmp)
            url="https://repo.maven.apache.org/maven2/g/a/1/a-1.pom"
            key=audit_module.sha(url.encode())
            (root/(key+".json")).write_text(json.dumps({"url":url,"status":200,"sha256":audit_module.sha(b"original")}))
            (root/(key+".bin")).write_bytes(b"changed")
            with mock.patch.object(audit_module,"CACHE",root):
                with self.assertRaisesRegex(ValueError,"cache hash mismatch"):
                    audit_module.Fetcher(False).fetch(url)

    def test_payload_and_receipt_tampering_rejected_by_tracked_pin(self):
        audit_module.CACHE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=audit_module.CACHE) as tmp:
            root=pathlib.Path(tmp)
            url="https://repo.maven.apache.org/maven2/g/a/1/a-1.pom"
            key=audit_module.sha(url.encode())
            pin={"url":url,"status":200,"sha256":audit_module.sha(b"original"),"bytes":8}
            edited={"url":url,"status":200,"sha256":audit_module.sha(b"changed"),"bytes":7}
            (root/(key+".json")).write_text(json.dumps(edited))
            (root/(key+".bin")).write_bytes(b"changed")
            with mock.patch.object(audit_module,"CACHE",root):
                with self.assertRaisesRegex(ValueError,"tracked input lock mismatch"):
                    audit_module.Fetcher(False,{url:pin}).fetch(url)

    def test_input_lock_bootstrap_never_overwrites(self):
        with tempfile.TemporaryDirectory(dir=audit_module.CACHE) as tmp:
            lock=pathlib.Path(tmp)/"INPUT_LOCK.json"
            lock.write_text("original")
            with mock.patch.object(audit_module,"INPUT_LOCK",lock):
                with self.assertRaisesRegex(ValueError,"refusing to overwrite"):
                    audit_module.initialize_input_lock()
            self.assertEqual(lock.read_text(),"original")

    def test_offline_missing_inputs_report_incomplete_and_fail(self):
        with tempfile.TemporaryDirectory(dir=audit_module.CACHE) as tmp:
            root=pathlib.Path(tmp)
            lock=root/"INPUT_LOCK.json"
            lock.write_text(json.dumps({"resources":[]}))
            with mock.patch.object(audit_module,"OUT",root), mock.patch.object(audit_module,"CACHE",root/"empty"), mock.patch.object(audit_module,"INPUT_LOCK",lock), mock.patch("sys.argv",["audit_transitives.py"]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(audit_module.main(),2)
            result=json.loads((root/"inventory.json").read_text())
            self.assertEqual(result["coverage"]["overall"],"incomplete")
            self.assertGreater(result["coverage"]["module_unknown"],0)
            self.assertGreater(result["coverage"]["pom_missing"],0)

    def test_zip_notice_scan_counts_nested_entries(self):
        inner=io.BytesIO()
        with zipfile.ZipFile(inner,"w") as archive:
            archive.writestr("META-INF/LICENSE.txt","terms")
            archive.writestr("a.class",b"not executed")
        outer=io.BytesIO()
        with zipfile.ZipFile(outer,"w") as archive:
            archive.writestr("classes.jar",inner.getvalue())
            archive.writestr("AndroidManifest.xml","manifest")
        counts=audit_module.collections.Counter()
        notices=audit_module.zip_notices(outer.getvalue(),scan=counts)
        self.assertEqual(counts["file_names_examined"],4)
        self.assertEqual(counts["archives_opened"],2)
        self.assertEqual(notices[0]["path"],"classes.jar!/META-INF/LICENSE.txt")


if __name__=="__main__": unittest.main()
