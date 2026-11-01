"""Query OSV for resolved Maven runtime coordinates (no source/data is sent)."""
from pathlib import Path
import json
import re
import urllib.request

root=Path(__file__).resolve().parents[1]
raw=(root/'artifacts/backend-dependencies.txt').read_bytes()
text=raw.decode('utf-8',errors='replace')
packages=[]
for line in text.splitlines():
    match=re.search(r'^\s*([^\s:]+):([^\s:]+):jar:([^\s:]+):(compile|runtime)',line)
    if match:
        packages.append({'package':{'ecosystem':'Maven','name':match[1]+':'+match[2]},'version':match[3]})
if not packages:
    raise SystemExit('No resolved Maven packages found. Run dependency:list first.')
request=urllib.request.Request('https://api.osv.dev/v1/querybatch',json.dumps({'queries':packages}).encode(),{'Content-Type':'application/json'})
with urllib.request.urlopen(request,timeout=60) as response:
    result=json.load(response)
findings=[]
for package,result_item in zip(packages,result['results']):
    if result_item.get('vulns'):
        findings.append({'package':package,'advisories':result_item['vulns']})
report={'packagesChecked':len(packages),'findings':findings,'source':'https://osv.dev/','note':'Known published vulnerabilities only; does not prove the absence of vulnerabilities.'}
(root/'artifacts/backend-osv-audit.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
