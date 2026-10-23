#!/usr/bin/env python3
"""Expose counts and synthetic test failures through accessible CI check annotations."""
import glob
import xml.etree.ElementTree as ET

patterns = [
    'core/build/test-results/test/TEST-*.xml',
    'app/build/test-results/testDebugUnitTest/TEST-*.xml',
    'app/build/outputs/androidTest-results/connected/**/*.xml',
]
for pattern in patterns:
    for file in glob.glob(pattern, recursive=True):
        root = ET.parse(file).getroot()
        if root.tag != 'testsuite':
            continue
        name = root.get('name', file)
        summary = ' '.join(f'{key}={root.get(key, "0")}' for key in ['tests','failures','errors','skipped'])
        print(f'::notice title=Test results::{name} {summary}')
        for case in root.findall('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                detail = (case.get('name', '') + ': ' + failure.get('message', '') + '\n' + (failure.text or ''))[:12000]
                print('::error::' + detail.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
