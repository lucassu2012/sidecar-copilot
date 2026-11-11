"""Read pinned source and prior metadata only; no download, build or device access."""
import argparse
import collections
import hashlib
import io
import json
import pathlib
import re
import tarfile

OUT = pathlib.Path(__file__).resolve().parent
SLICE = OUT.parents[2]
LOCK = OUT / 'inputs.lock.json'
UVC = 'fcdae5f9a194e2111d57019176b53470a120415b'
YUV = '3e38ce50589d9319badc0501f96d6c5b2b177472'
JAVA_SEEDS = ['com.serenegiant.usb.UVCCamera', 'com.serenegiant.usb.USBMonitor', 'com.serenegiant.usb.UVCControl']
WHOLE_ROOTS = ['androidx.core:core:1.13.1', 'androidx.legacy:legacy-support-v4:1.0.0', 'androidx.appcompat:appcompat:1.6.1', 'androidx.annotation:annotation:1.8.0']
GENERATED = ['capabilities.json', 'JAVA_CANDIDATES.md', 'DECLARED_DEPENDENCIES.md', 'NATIVE_SOURCES.md']


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked(data, entry):
    if len(data) != entry['bytes'] or sha(data) != entry['sha256']:
        raise ValueError('pinned input mismatch: ' + entry['id'])
    return data


def read_tar(data, prefix=''):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive:
            if not member.isfile():
                continue
            if not member.name.startswith(prefix):
                raise ValueError('unexpected archive prefix')
            name = member.name[len(prefix):]
            if name in result or pathlib.PurePosixPath(name).is_absolute() or '..' in pathlib.PurePosixPath(name).parts:
                raise ValueError('unsafe or duplicate archive entry')
            if member.size > 16 * 1024 * 1024:
                raise ValueError('source member exceeds bound')
            result[name] = archive.extractfile(member).read()
    return result


def without_literals(source):
    return re.sub(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', ' ', source, flags=re.S)


def java_candidates(files):
    classes = {}
    for path, data in files.items():
        if path.startswith('libuvccamera/src/main/java/') and path.endswith('.java'):
            raw = data.decode('utf-8')
            package = re.search(r'\bpackage\s+([\w.]+)\s*;', raw).group(1)
            classes[package + '.' + pathlib.PurePosixPath(path).stem] = (path, raw)
    pending = collections.deque(JAVA_SEEDS)
    seen, edges, external, manifest = set(), [], [], []
    while pending:
        current = pending.popleft()
        if current in seen:
            continue
        seen.add(current)
        path, raw = classes[current]
        source = without_literals(raw)
        package = current.rsplit('.', 1)[0]
        deps = set()
        imports = re.findall(r'^import\s+(?:static\s+)?([\w.*]+)\s*;', source, flags=re.M)
        for imported in imports:
            candidate = imported
            while candidate and candidate not in classes:
                candidate = candidate.rsplit('.', 1)[0] if '.' in candidate else ''
            if candidate:
                deps.add(candidate)
            else:
                kind = 'platform' if imported.startswith(('java.', 'android.', 'org.json.', 'org.xmlpull.')) else 'generated' if imported == 'com.serenegiant.uvccamera.BuildConfig' else 'third_party'
                external.append({'from': current, 'import': imported, 'kind': kind})
        for other in classes:
            if other != current and other.rsplit('.', 1)[0] == package and re.search(r'\b' + re.escape(other.rsplit('.', 1)[1]) + r'\b', source):
                deps.add(other)
        for dep in sorted(deps):
            edges.append({'from': current, 'to': dep})
            pending.append(dep)
        manifest.append({'class': current, 'path': path, 'bytes': len(files[path]), 'sha256': sha(files[path]),
                         'url': 'https://github.com/shiyinghan/UVCAndroid/blob/' + UVC + '/' + path,
                         'imports': imports})
    return {'seeds': JAVA_SEEDS, 'method': 'conservative literal import and same-package simple-name scan with comments/strings removed; not javac, JNI linkage or runtime reachability',
            'manifest': sorted(manifest, key=lambda r: r['class']), 'edges': edges, 'external_imports': external}


def declared_subset(prior, roots):
    models = prior['pom_models']
    pending = collections.deque(roots)
    selected, metadata = set(), set()
    while pending:
        gav = pending.popleft()
        if gav in selected:
            continue
        selected.add(gav)
        if not models.get(gav):
            raise ValueError('missing previously inspected POM: ' + gav)
        for dep in models[gav]['dependencies']:
            if dep['followed']:
                pending.append(dep['target'])
            elif dep['scope'] in ('compile', 'runtime'):
                raise ValueError('unresolved POM runtime declaration: ' + gav)
    pending = collections.deque(selected)
    while pending:
        gav = pending.popleft()
        if gav in metadata:
            continue
        metadata.add(gav)
        model = models[gav]
        if model['parent']:
            pending.append(model['parent'])
        pending.extend(model['bom_imports'])
    versions = collections.defaultdict(set)
    for gav in selected:
        g, a, v = gav.split(':')
        versions[g + ':' + a].add(v)
    artifacts = {r['coordinate']: r for r in prior['artifact_inspections']}
    return {'roots': roots, 'scope': 'prior effective POM compile/runtime union including optional edges; no exclusions applied, no version mediation, no actual Gradle variant or packaging resolution',
            'coordinates': sorted(selected), 'metadata_only': sorted(metadata - selected),
            'conflicts': {g: sorted(v) for g, v in sorted(versions.items()) if len(v) > 1},
            'components': [{'coordinate': g, 'pom_url': models[g]['pom_url'], 'pom_sha256': models[g]['pom_sha256'],
                            'licenses_pom_only': models[g]['licenses'], 'artifact_url': artifacts.get(g, {}).get('url'),
                            'artifact_sha256': artifacts.get(g, {}).get('sha256')} for g in sorted(metadata)]}


def native_sources(files, official):
    components = ('UVCCamera', 'libusb', 'libuvc', 'libjpeg-turbo', 'rapidjson')
    result = []
    for name in components + ('official_libyuv',):
        source = official if name == 'official_libyuv' else files
        prefix = '' if name == 'official_libyuv' else 'libuvccamera/src/main/jni/' + name + '/'
        selected = [(p, b) for p, b in source.items() if p.startswith(prefix)]
        manifest = [{'path': p, 'bytes': len(b), 'sha256': sha(b)} for p, b in sorted(selected)]
        build_files = []
        for path, data in selected:
            if not path.endswith(('Android.mk', 'Application.mk', 'libusb.mk', 'CMakeLists.txt')):
                continue
            lines = data.decode('utf8', 'replace').splitlines()
            build_files.append({'path': path, 'sha256': sha(data), 'lines': [{'line': n, 'text': line} for n, line in enumerate(lines, 1)]})
        result.append({'component': name, 'revision': YUV if name == 'official_libyuv' else UVC,
                       'manifest_scope': 'all regular files in component subtree; includes tests/utilities/build inputs, not a final compiled source closure',
                       'manifest': manifest, 'build_files': sorted(build_files, key=lambda r: r['path'])})
    return result


def documents(report):
    java = ['# Java source candidates', '', 'Static source-retention candidates, not a verified compilation/linkage closure. Generated BuildConfig and platform API classes are separate inputs.', '', '| Class | Fixed source | SHA-256 |', '| --- | --- | --- |']
    for row in report['java_candidates']['manifest']:
        java.append(f"| {row['class']} | [{row['path']}]({row['url']}) | `{row['sha256']}` |")
    dep = ['# Declared Java dependency subsets', '', 'Derived only from the locked prior publication inventory; source candidate roots differ from the published root. POM license names are declarations, not complete source notices. This is not Gradle resolution or an APK contents claim.', '']
    for label, group in report['declared_dependency_subsets'].items():
        dep += ['## ' + label, '', f"Runtime coordinate/version union: {len(group['coordinates'])}; parent/BOM-only models: {len(group['metadata_only'])}; conflicting modules: {len(group['conflicts'])}.", '', '| Coordinate | POM-declared license | POM identity | Archive SHA-256 |', '| --- | --- | --- | --- |']
        for row in group['components']:
            names = '; '.join(r['name'] for r in row['licenses_pom_only']) or 'none declared in inspected model'
            dep.append(f"| {row['coordinate']} | {names} | [{row['pom_sha256']}]({row['pom_url']}) | {row['artifact_sha256'] or 'metadata only'} |")
        dep.append('')
    native = ['# Native subtree inventory', '', 'Every regular file in each retained candidate subtree has a SHA-256 in capabilities.json. Whole subtrees include unused tests/tools; build lists are static source evidence and have not been evaluated by ndk-build/CMake.', '', '| Candidate subtree | Fixed revision | Regular files | Build files inspected |', '| --- | --- | ---: | --- |']
    for component in report['native_sources']:
        native.append(f"| {component['component']} | {component['revision']} | {len(component['manifest'])} | {'; '.join(r['path'] for r in component['build_files'])} |")
    native += ['', 'The target official libyuv subtree replaces the vendored modified libyuv; no mixing of their source/header objects is proposed. No source files were copied into the build or application.', '']
    return {'capabilities.json': json.dumps(report, indent=2, ensure_ascii=False) + '\n',
            'JAVA_CANDIDATES.md': '\n'.join(java) + '\n', 'DECLARED_DEPENDENCIES.md': '\n'.join(dep), 'NATIVE_SOURCES.md': '\n'.join(native)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-cache', type=pathlib.Path, required=True, help='read-only prior libyuv archive cache')
    parser.add_argument('--write-report', action='store_true', help='explicitly generate/update all derived outputs')
    args = parser.parse_args()
    if not args.write_report and any(not (OUT / name).is_file() for name in GENERATED):
        raise FileNotFoundError('derived reports missing; explicit --write-report is required for generation')
    locked = LOCK.read_bytes()
    manifest = json.loads(locked)
    raw = {}
    for entry in manifest['inputs']:
        path = args.source_cache / entry['path'] if entry['kind'] == 'archive' else SLICE / entry['path']
        raw[entry['id']] = checked(path.read_bytes(), entry)
    uvc = read_tar(raw['uvc_archive'], 'UVCAndroid-' + UVC + '/')
    official = read_tar(raw['libyuv_archive'])
    java = java_candidates(uvc)
    if len(java['manifest']) != 12:
        raise ValueError('Java candidate set changed; review before updating expectation')
    prior = json.loads(raw['transitives'])
    groups = {'whole_source_module_four_roots': declared_subset(prior, WHOLE_ROOTS),
              'isolated_java_annotation_root': declared_subset(prior, ['androidx.annotation:annotation:1.8.0'])}
    local_inputs = [entry for entry in manifest['inputs'] if entry['kind'] == 'local']
    report = {'schema': 1, 'baseline': manifest['baseline'], 'inputs_lock_sha256': sha(locked),
              'scope': 'static feasibility and source inventory only; no new dependency resolved, compiled or executed',
              'java_candidates': java, 'declared_dependency_subsets': groups, 'native_sources': native_sources(uvc, official),
              'local_inputs': local_inputs,
              'local_libs_jar_files': [p for p in sorted(uvc) if p.startswith('libuvccamera/libs/') and p.endswith('.jar')],
              'fixture': {'width': 640, 'height': 360, 'encoding': 'RGBA', 'bytes_per_frame': 921600,
                          'owned_array_limit_bytes': 7340032, 'not_a_production_contract': True},
              'cost_examples': {'rgba_1920_1080_bytes': 8294400, 'rgba_1080p30_one_copy_read_plus_write_bytes_per_s': 497664000,
                                'measurement': False, 'meaning': 'ideal CPU memory traffic formula 8*width*height*USB decoded fps, excluding other stages'},
              'actual_native_build': 'not_run', 'actual_gradle_resolution': 'not_run', 'hardware': 'not_accessed'}
    output = documents(report)
    for name, contents in output.items():
        if args.write_report:
            (OUT / name).write_text(contents, encoding='utf8')
        elif (OUT / name).read_text('utf8') != contents:
            raise ValueError('derived output changed: ' + name)
    print(json.dumps({'verified_inputs': len(manifest['inputs']), 'java_candidates': len(java['manifest']),
                      'whole_source_pom_union': len(groups['whole_source_module_four_roots']['coordinates']),
                      'annotation_pom_union': len(groups['isolated_java_annotation_root']['coordinates']),
                      'native_components': len(report['native_sources']), 'mode': 'static-only'}, indent=2))


if __name__ == '__main__':
    main()
