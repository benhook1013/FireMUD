#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export FIREMUD_REPO_ROOT="$ROOT_DIR"
python3 - <<'PY'
from __future__ import annotations
import json, os, re
from pathlib import Path
import yaml

root=Path(os.environ['FIREMUD_REPO_ROOT']); workflows=root/'.github/workflows'; actions=root/'.github/actions'
def fail(m): raise SystemExit(m)
def authority(path):
 out={}
 for line in path.read_text().splitlines():
  if not line or line.startswith('#'): continue
  if not re.fullmatch(r'[A-Z][A-Z0-9_]*=[A-Za-z0-9._:-]+',line): fail(f'{path}: invalid authority line: {line}')
  k,v=line.split('=',1)
  if k in out: fail(f'{path}: duplicate {k}')
  out[k]=v
 return out

node=(root/'.node-version').read_text().strip()
if not re.fullmatch(r'24\.\d+\.\d+',node): fail('.node-version must pin an exact Node 24 release')
python=(root/'.python-version').read_text().strip()
if not re.fullmatch(r'3\.14\.\d+',python): fail('.python-version must pin an exact Python 3.14 release')
for req in ('config/docs/requirements.txt','config/python/ci-requirements.txt','config/python/smoke-requirements.txt'):
 lines=(root/req).read_text().splitlines()
 if not lines or any(not re.fullmatch(r'[A-Za-z0-9_-]+==[^=\s]+',x) for x in lines): fail(f'{req} must contain only exact pins')

ap=root/'config/workflow-tool-versions.env'; a=authority(ap)
versions=['KUBECTL','HELM','GH','BUF','KUBECONFORM','VELERO','ACTIONLINT','TRIVY','LYCHEE','ORT','ZAP']
if any(not re.fullmatch(r'\d+\.\d+\.\d+',a.get(f'{x}_VERSION','')) for x in versions): fail('all workflow tools must have exact versions')
pairs={'HELM':'HELM_LINUX_AMD64','GH':'GH_LINUX_AMD64','BUF':'BUF_LINUX_X86_64','KUBECONFORM':'KUBECONFORM_LINUX_AMD64','VELERO':'VELERO_LINUX_AMD64','LYCHEE':'LYCHEE_LINUX_X86_64_MUSL'}
for tool,stem in pairs.items():
 if a.get(f'{stem}_CHECKSUM_VERSION') != a[f'{tool}_VERSION']: fail(f'{tool} checksum version is stale')
 if not re.fullmatch(r'[0-9a-f]{64}',a.get(f'{stem}_SHA256','')): fail(f'{tool} checksum is invalid')
for tool in ('ORT','ZAP'):
 if not re.fullmatch(r'sha256:[0-9a-f]{64}',a.get(f'{tool}_DIGEST','')): fail(f'{tool} digest is invalid')
expected={f'{x}_VERSION' for x in versions}|{f'{s}_{suffix}' for s in pairs.values() for suffix in ('CHECKSUM_VERSION','SHA256')}|{'ORT_DIGEST','ZAP_DIGEST'}
if set(a)!=expected: fail('workflow tool authority has unexpected or missing keys')

def load(path):
 d=yaml.safe_load(path.read_text()); return d if isinstance(d,dict) else {}
def run_has_gh(run): return bool(re.search(r'(^|[;&|()\s])gh(?:\s|$)',run))
def referenced_gh_script(run):
 for name in re.findall(r'(?:\./)?(dev-tools/[A-Za-z0-9_./-]+)',run):
  p=root/name.rstrip('"\'')
  if p.is_file() and run_has_gh(p.read_text()): return True
 return False

node_count=python_count=gh_count=0
for path in sorted(workflows.glob('*.yml')):
 for job_name,job in (load(path).get('jobs') or {}).items():
  if not isinstance(job,dict): continue
  checkout=py=gh=loader=False
  for step in job.get('steps',[]):
   if not isinstance(step,dict): continue
   uses=str(step.get('uses','')); run=str(step.get('run',''))
   if uses.startswith('actions/checkout@'): checkout=True
   if uses=='./.github/actions/load-workflow-tool-versions':
    if not checkout: fail(f'{path.name}:{job_name}: loader before checkout')
    loader=True
   if uses.startswith('actions/setup-node@'):
    node_count+=1
    if not checkout or step.get('with',{}).get('node-version-file')!='.node-version' or 'node-version' in step.get('with',{}): fail(f'{path.name}:{job_name}: invalid Node authority consumption')
   if uses.startswith('actions/setup-python@'): fail(f'{path.name}:{job_name}: bypasses canonical setup-python action')
   if uses=='./.github/actions/setup-python':
    python_count+=1
    if not checkout: fail(f'{path.name}:{job_name}: Python setup before checkout')
    py=True
   if uses=='./.github/actions/setup-gh':
    gh_count+=1
    if not checkout: fail(f'{path.name}:{job_name}: gh setup before checkout')
    gh=True
   if 'python3' in run and not py: fail(f'{path.name}:{job_name}: python3 uses ambient runner Python')
   if (run_has_gh(run) or referenced_gh_script(run)) and not gh: fail(f'{path.name}:{job_name}: gh consumer is not preceded by canonical setup-gh')
   if uses.startswith('oss-review-toolkit/ort-ci-github-action@'):
    if not loader or step.get('with',{}).get('image')!='${{ steps.workflow-tool-versions.outputs.ort-image }}': fail(f'{path.name}:{job_name}: ORT bypasses authority')
if not min(node_count,python_count,gh_count): fail('expected Node, Python, and gh consumers')

for path in sorted(actions.glob('*/action.yml')):
 setup_py=setup_gh=False
 for step in load(path).get('runs',{}).get('steps',[]):
  uses=str(step.get('uses','')); run=str(step.get('run',''))
  setup_py |= uses=='./.github/actions/setup-python'; setup_gh |= uses=='./.github/actions/setup-gh'
  if uses.startswith('actions/setup-python@') and path.parent.name!='setup-python': fail(f'{path}: bypasses setup-python wrapper')
  if 'python3' in run and not setup_py: fail(f'{path}: Python consumer lacks setup')
  if run_has_gh(run) and not setup_gh: fail(f'{path}: gh consumer lacks setup')

text='\n'.join(p.read_text() for p in workflows.glob('*.yml'))
for forbidden in ('python-version:','ruff==','PyYAML\n','websocket-client\n','aquasecurity/trivy/main','zaproxy:stable','VELERO_VERSION=v','KUBECONFORM_VERSION="v','BUF_VERSION: \'1.30.0\''):
 if forbidden in text: fail(f'workflow contains stale or duplicated authority: {forbidden}')
required={
 'ci.yml':('buf-version','buf-linux-x86-64-sha256','kubeconform-version','kubeconform-linux-amd64-sha256','actionlint-version'),
 'docs.yml':('lychee-version',), 'manual-backup-restore.yml':('velero-version','velero-linux-amd64-sha256'),
 'security.yml':('trivy-version',), 'weekly-security-scan.yml':('trivy-version',), 'zap-baseline.yml':('zap-image',)}
for name,needles in required.items():
 data=(workflows/name).read_text()
 if any(n not in data for n in needles): fail(f'{name} does not consume all canonical tool outputs')
if f'image: velero/velero:v{a["VELERO_VERSION"]}' not in (root/'k8s/velero/verify-backups-cronjob.yaml').read_text(): fail('Velero image projection is stale')

renovate=json.loads((root/'renovate.json').read_text())
if not {'nodenv','pyenv','pip_requirements','custom.regex'} <= set(renovate['enabledManagers']): fail('Renovate managers incomplete')
if len(renovate.get('customManagers',[]))!=2: fail('Renovate must define version and image authority managers')
matched=[]
for manager in renovate['customManagers']:
 pattern=re.compile(manager['matchStrings'][0].replace('(?<','(?P<'))
 matched += [m.group('currentValue') for m in pattern.finditer(ap.read_text())]
if set(matched)!={a[f'{x}_VERSION'] for x in versions}: fail('Renovate does not discover every workflow tool authority')

proto=(root/'gradle/proto-convention.gradle').read_text()
if 'protobuf-gradle-plugin 0.10.0' in proto or 'libs.plugins.protobuf' not in proto or '${gradle.gradleVersion}' not in proto: fail('protobuf diagnostic must derive both canonical versions')
for action in ('setup-gh','setup-helm'):
 data=(actions/action/'action.yml').read_text()
 if 'sha256sum --check --status' not in data: fail(f'{action} must verify its archive')
if 'RUNNER_OS' not in (actions/'setup-gh/action.yml').read_text() or 'RUNNER_ARCH' not in (actions/'setup-gh/action.yml').read_text(): fail('setup-gh must reject unsupported platforms')
lychee=(root/'dev-tools/docs/link-check.sh').read_text()
for required in ('source "$ROOT_DIR/config/workflow-tool-versions.env"','/lychee/${LYCHEE_VERSION}','LYCHEE_LINUX_X86_64_MUSL_SHA256','sha256sum --check --status'):
 if required not in lychee: fail(f'local Lychee installer does not consume its authority: {required}')
print('Workflow version authority contract passed')
PY
