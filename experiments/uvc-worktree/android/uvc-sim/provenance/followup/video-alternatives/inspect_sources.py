"""Pinned static native-source inventory. Never extracts, compiles or executes inputs."""
import argparse
import hashlib
import io
import json
import pathlib
import re
import tarfile
import urllib.parse
import urllib.request

OUT = pathlib.Path(__file__).resolve().parent
CACHE = OUT.parents[2] / 'build' / 'native-research' / 'video-alternatives'
LOCK = OUT / 'inputs.lock.json'
UVC = 'fcdae5f9a194e2111d57019176b53470a120415b'
YUV = '3e38ce50589d9319badc0501f96d6c5b2b177472'
CUSTOM = ('YUY2ToABGR', 'MJPGToABGR', 'ABGRToRGB565', 'ABGRToYUY2')
NATIVE_EXTENSIONS = ('.c', '.cc', '.cpp', '.cxx', '.h', '.hpp', '.mk')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(data, record):
    if len(data) != record['size'] or sha(data) != record['sha256']:
        raise ValueError('locked input identity mismatch: ' + record['name'])
    return data


def load(record, existing_cache, online):
    candidates = [CACHE / record['name']]
    if existing_cache:
        candidates.append(existing_cache / record['name'])
    for candidate in candidates:
        if candidate.is_file():
            return verify(candidate.read_bytes(), record)
    if not online:
        raise FileNotFoundError('offline input missing: ' + record['name'])
    parsed = urllib.parse.urlparse(record['url'])
    if parsed.scheme != 'https' or parsed.hostname not in ('codeload.github.com', 'chromium.googlesource.com', 'raw.githubusercontent.com', 'api.github.com'):
        raise ValueError('unapproved source host')
    request = urllib.request.Request(record['url'], headers={'User-Agent': 'Sidecar-Static-Native-Research'})
    with urllib.request.urlopen(request, timeout=30) as response:
        final = urllib.parse.urlparse(response.url)
        if final.scheme != 'https' or final.hostname != parsed.hostname:
            raise ValueError('unexpected source redirect')
        data = response.read(record['size'] + 1)
    verify(data, record)
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / record['name']).write_bytes(data)
    return data


def archive_files(data, prefix=''):
    """Yield bounded regular files; never extract members or follow links."""
    seen = set()
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
        for member in archive:
            if not member.isfile():
                continue
            path = member.name
            if prefix:
                if not path.startswith(prefix):
                    raise ValueError('unexpected source archive root')
                path = path[len(prefix):]
            if path in seen or pathlib.PurePosixPath(path).is_absolute() or '..' in pathlib.PurePosixPath(path).parts:
                raise ValueError('duplicate/unsafe archive name')
            seen.add(path)
            if member.size > 16 * 1024 * 1024:
                raise ValueError('source member exceeds inspection bound: ' + path)
            yield path, archive.extractfile(member).read()


UVC_SELECTED = {
    'libuvccamera/build.gradle',
    'libuvccamera/src/main/jni/Android.mk',
    'libuvccamera/src/main/jni/Application.mk',
    'libuvccamera/src/main/jni/CMakeLists.txt',
    'libuvccamera/src/main/jni/UVCCamera/Android.mk',
    'libuvccamera/src/main/jni/UVCCamera/CMakeLists.txt',
    'libuvccamera/src/main/jni/UVCCamera/ConvertHelper.cpp',
    'libuvccamera/src/main/jni/UVCCamera/ConvertHelper.h',
    'libuvccamera/src/main/jni/UVCCamera/UVCPreview.cpp',
    'libuvccamera/src/main/jni/libuvc/src/frame.c',
    'libuvccamera/src/main/jni/libuvc/src/frame-mjpeg.c',
    'libuvccamera/src/main/jni/libuvc/android/jni/Android.mk',
    'libuvccamera/src/main/jni/libuvc/include/libuvc/libuvc.h',
    'libuvccamera/src/main/jni/libuvc/LICENSE.txt',
    'libuvccamera/src/main/jni/libjpeg-turbo/turbojpeg.h',
    'libuvccamera/src/main/jni/libjpeg-turbo/LICENSE.md',
    'libuvccamera/src/main/jni/libjpeg-turbo/CMakeLists.txt',
    'libuvccamera/src/main/jni/libyuv/Android.mk',
    'libuvccamera/src/main/jni/libyuv/source/convert_argb.cc',
    'libuvccamera/src/main/jni/libyuv/source/convert_from_argb.cc',
    'libuvccamera/src/main/jni/libyuv/source/convert_jpeg.cc',
    'libuvccamera/src/main/jni/libyuv/source/row_common.cc',
}
YUV_SELECTED = {
    'LICENSE', 'PATENTS', 'AUTHORS', 'Android.mk', 'CMakeLists.txt', 'docs/formats.md',
    'source/convert_argb.cc', 'source/convert_from_argb.cc', 'source/convert_jpeg.cc',
    'source/row_common.cc', 'source/planar_functions.cc', 'source/mjpeg_decoder.cc',
    'include/libyuv/convert_argb.h', 'include/libyuv/convert_from_argb.h',
    'include/libyuv/planar_functions.h',
}
TOKENS = CUSTOM + (
    'YUY2ToARGB', 'ARGBToABGR', 'ABGRToARGB', 'ARGBToRGB565', 'ARGBToYUY2',
    'YUY2ToI422', 'I422ToABGR', 'MJPGToARGB', 'I420ToARGB', 'I422ToARGB',
    'I444ToARGB', 'I400ToARGB', 'I420ToABGR', 'I444ToABGR', 'kYuvI601Constants',
    'kYvuI601Constants', 'uvc_mjpeg2rgbx_tj', 'uvc_mjpeg2rgbx_new',
    'uvc_yuyv2rgbx', 'uvc_rgbx_to_yuyv', 'uvc_rgbx_to_rgb565',
    'PIXEL_FORMAT_RGBX', 'TJPF_RGBX', 'TJPF_RGBA', 'JCS_EXT_RGBA',
    'USE_STRIDE', 'uvc_ensure_frame_size', 'NewDirectByteBuffer', 'recycle_frame',
    'LOCAL_SHARED_LIBRARIES', 'LOCAL_SRC_FILES', 'HAVE_JPEG', 'align_buffer_64',
    'externalNativeBuild', 'width *= height', 'kShuffleMaskABGRToARGB',
)


def inspect_archive(data, project):
    selected = UVC_SELECTED if project == 'UVCAndroid' else YUV_SELECTED
    prefix = 'UVCAndroid-' + UVC + '/' if project == 'UVCAndroid' else ''
    records, occurrences, manifest, extension_sites = [], [], [], []
    scanned = 0
    for path, content in archive_files(data, prefix):
        if path.endswith(NATIVE_EXTENSIONS):
            scanned += 1
            source = content.decode('utf-8', 'replace')
            manifest.append({'path': path, 'sha256': sha(content), 'size': len(content)})
            for number, line in enumerate(source.splitlines(), 1):
                for symbol in CUSTOM:
                    if re.search(r'\b' + symbol + r'\b', line):
                        row = {'symbol': symbol, 'path': path, 'line': number, 'text': line.strip()}
                        occurrences.append(row)
                        if project == 'UVCAndroid' and '/jni/libyuv/' not in path:
                            extension_sites.append(row)
        if path not in selected:
            continue
        lines = content.decode('utf-8', 'replace').splitlines()
        matches = [{'line': n, 'text': line.strip()} for n, line in enumerate(lines, 1)
                   if any(token in line for token in TOKENS)]
        url = ('https://github.com/shiyinghan/UVCAndroid/blob/' + UVC + '/' + path if project == 'UVCAndroid'
               else 'https://chromium.googlesource.com/libyuv/libyuv/+/' + YUV + '/' + path)
        records.append({'path': path, 'url': url, 'sha256': sha(content), 'size': len(content),
                        'line_count': len(lines), 'selected_lines': matches})
    missing = selected - {r['path'] for r in records}
    if missing:
        raise ValueError('required source files missing: ' + repr(sorted(missing)))
    return {'project': project, 'native_text_files_scanned': scanned,
            'scan_method': 'literal identifier occurrences in native source/header/make files; not a compiled call graph',
            'native_file_manifest': manifest, 'custom_symbol_occurrences': occurrences,
            'custom_symbol_occurrences_outside_vendored_libyuv': extension_sites,
            'selected_file_evidence': sorted(records, key=lambda r: r['path'])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--existing-cache', type=pathlib.Path, help='optional read-only prior libyuv cache')
    parser.add_argument('--online', action='store_true', help='retrieve only missing locked inputs')
    parser.add_argument('--write-report', action='store_true', help='explicitly replace generated evidence.json after all checks succeed')
    args = parser.parse_args()
    report_path = OUT / 'evidence.json'
    if not args.write_report and not report_path.is_file():
        raise FileNotFoundError('expected evidence.json missing; use --write-report only for explicit evidence generation')
    lock_bytes = LOCK.read_bytes()
    inputs = json.loads(lock_bytes)['inputs']
    results = []
    supplemental = []
    for record in inputs:
        data = load(record, args.existing_cache, args.online)
        if record.get('archive_project'):
            results.append(inspect_archive(data, record['archive_project']))
        else:
            supplemental.append({'name': record['name'], 'url': record['url'], 'sha256': sha(data),
                                 'size': len(data), 'selected_lines': [
                {'line': n, 'text': line.strip()} for n, line in enumerate(data.decode('utf8').splitlines(), 1)
                if any(token in line for token in ('TJPF_RGBX', 'TJPF_RGBA', 'undefined when decompressing', 'guaranteed to be 0xFF', 'COPYRIGHT', 'copyright', 'License', 'license'))]})
    vendor = next(r for r in results if r['project'] == 'UVCAndroid')
    upstream = next(r for r in results if r['project'] == 'official_libyuv')
    if upstream['custom_symbol_occurrences']:
        raise ValueError('expected official archive to contain none of the four custom APIs')
    symbols = [r['symbol'] for r in vendor['custom_symbol_occurrences_outside_vendored_libyuv']]
    if sorted(symbols) != ['ABGRToRGB565', 'ABGRToYUY2']:
        raise ValueError('expected literal candidate symbol set changed')
    jpeg_matches = {}
    for suffix in ('turbojpeg.h', 'LICENSE.md'):
        original = next(r for r in vendor['selected_file_evidence'] if r['path'].endswith('/libjpeg-turbo/' + suffix))
        official = next(r for r in supplemental if r['name'].endswith('-' + suffix))
        jpeg_matches[suffix] = original['sha256'] == official['sha256'] and original['size'] == official['size']
        if not jpeg_matches[suffix]:
            raise ValueError('pinned JPEG header/license correspondence changed')
    report = {'schema': 1, 'scope': 'source-only static observations; no compilation or runtime equivalence evidence',
              'inputs_lock_sha256': sha(lock_bytes), 'inputs': inputs, 'projects': results,
              'supplemental_official_files': supplemental,
              'vendor_vs_official_libjpeg_turbo_2_1_2_selected_file_byte_matches': jpeg_matches,
              'summary': {'source_inputs_verified': len(inputs), 'custom_API_occurrences_outside_libyuv': len(symbols),
                          'literal_custom_symbols_outside_vendor': sorted(symbols),
                          'official_archive_has_custom_APIs': False,
                          'native_build_executed': False, 'hardware_tested': False}}
    serialized = json.dumps(report, indent=2, ensure_ascii=False) + '\n'
    if args.write_report:
        report_path.write_text(serialized, encoding='utf-8')
    else:
        if json.loads(report_path.read_text('utf-8')) != report:
            raise ValueError('generated static evidence differs; inspect before explicitly regenerating')
    print(json.dumps(report['summary'], indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
