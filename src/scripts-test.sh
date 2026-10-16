#!/bin/sh
# Run core tests and print a compact summary. usage: ./scripts-test.sh [gradle test filter]
cd "$(dirname "$0")"
# needs JAVA_HOME pointing to a JDK 21
./gradlew :core:test --console=plain -q ${1:+--tests "$1"} > build-test.log 2>&1; rc=$?
python3 - <<'PY'
import glob,re,html
tot=[0,0,0,0]
for f in sorted(glob.glob('core/build/test-results/test/*.xml')):
    s=open(f).read(); m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',s)
    n=list(map(int,m.groups())); tot=[a+b for a,b in zip(tot,n)]
    print(f.split('TEST-')[-1][:-4].split('.')[-1], n)
    for mm in re.finditer(r'<testcase name="([^"]+)"[^>]*>\s*<(failure|error) message="([^"]*)"',s):
        print('   FAIL', mm.group(1), '::', html.unescape(mm.group(3))[:300])
print('TOTAL tests=%d skipped=%d failures=%d errors=%d'%tuple(tot))
PY
[ $rc -ne 0 ] && grep -E "^e:|error:|FAILED|What went wrong" -A3 build-test.log | head -30
exit $rc
