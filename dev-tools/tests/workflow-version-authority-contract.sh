#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export FIREMUD_REPO_ROOT="$ROOT_DIR"
python3 - <<'PY'
from __future__ import annotations
import json, os, re, subprocess, tempfile
from collections import Counter
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
for req in ('config/docs/requirements.txt','config/python/ci-requirements.txt','config/python/smoke-requirements.txt','config/python/yaml-requirements.txt'):
 lines=(root/req).read_text().splitlines()
 if not lines or any(not (re.fullmatch(r'[A-Za-z0-9_-]+==[^=\s]+',x) or (req.endswith('ci-requirements.txt') and x=='-r yaml-requirements.txt')) for x in lines): fail(f'{req} must contain only exact pins or its canonical YAML include')
if 'PyYAML==' in (root/'config/python/ci-requirements.txt').read_text(): fail('PyYAML must have one canonical requirements authority')

ap=root/'config/workflow-tool-versions.env'; a=authority(ap)
versions=['KUBECTL','HELM','GH','BUF','KUBECONFORM','VELERO','ACTIONLINT','TRIVY','LYCHEE','ORT','ZAP']
if any(not re.fullmatch(r'\d+\.\d+\.\d+',a.get(f'{x}_VERSION','')) for x in versions): fail('all workflow tools must have exact versions')
pairs={'HELM':'HELM_LINUX_AMD64','GH':'GH_LINUX_AMD64','BUF':'BUF_LINUX_X86_64','KUBECONFORM':'KUBECONFORM_LINUX_AMD64','VELERO':'VELERO_LINUX_AMD64','LYCHEE':'LYCHEE_LINUX_X86_64_MUSL'}
for tool,stem in pairs.items():
 if a.get(f'{stem}_CHECKSUM_VERSION') != a[f'{tool}_VERSION']: fail(f'{tool} checksum version is stale')
 if not re.fullmatch(r'[0-9a-f]{64}',a.get(f'{stem}_SHA256','')): fail(f'{tool} checksum is invalid')
for tool in ('VELERO_IMAGE','ORT','ZAP'):
 if not re.fullmatch(r'sha256:[0-9a-f]{64}',a.get(f'{tool}_DIGEST','')): fail(f'{tool} digest is invalid')
expected={f'{x}_VERSION' for x in versions}|{f'{s}_{suffix}' for s in pairs.values() for suffix in ('CHECKSUM_VERSION','SHA256')}|{'VELERO_IMAGE_DIGEST','ORT_DIGEST','ZAP_DIGEST'}
if set(a)!=expected: fail('workflow tool authority has unexpected or missing keys')

loader=yaml.safe_load((actions/'load-workflow-tool-versions/action.yml').read_text())
loader_runs=[step.get('run') for step in loader.get('runs',{}).get('steps',[]) if isinstance(step,dict) and isinstance(step.get('run'),str)]
if len(loader_runs)!=1: fail('workflow authority loader must define exactly one validation script')
loader_run=loader_runs[0]
with tempfile.TemporaryDirectory() as temporary:
 workspace=Path(temporary)/'workspace'; workspace.mkdir()
 authority_path=workspace/'config'; authority_path.mkdir()
 output_path=Path(temporary)/'github-output'; output_path.write_text('sentinel\n')
 env={**os.environ,'GITHUB_WORKSPACE':str(workspace),'GITHUB_OUTPUT':str(output_path)}
 (authority_path/'workflow-tool-versions.env').write_text(ap.read_text())
 canonical=subprocess.run(['bash','-c',loader_run],cwd=temporary,env=env,capture_output=True,text=True)
 if canonical.returncode!=0: fail(f'workflow authority loader rejects canonical authority: {canonical.stderr.strip()}')
 (authority_path/'workflow-tool-versions.env').write_text(ap.read_text()+'GITHUB_OUTPUT=redirected.output\n')
 output_path.write_text('sentinel\n')
 unsupported=subprocess.run(['bash','-c',loader_run],cwd=temporary,env=env,capture_output=True,text=True)
 if unsupported.returncode==0: fail('workflow authority loader accepts unsupported assignments')
 if output_path.read_text()!='sentinel\n' or (Path(temporary)/'redirected.output').exists(): fail('unsupported authority assignment can redirect workflow outputs')

def load(path):
 d=yaml.safe_load(path.read_text()); return d if isinstance(d,dict) else {}
def run_has_gh(run): return bool(re.search(r'(^|[;&|()\s])gh(?:\s|$)',run))
def references(text):
 result=set()
 for name in re.findall(r'(?:\./)?((?:dev-tools|services)/[A-Za-z0-9_./-]+)',text):
  path=root/name.rstrip('"\'')
  if path.is_file(): result.add(path)
 return result
def helper_text(text, seen=None):
 seen=set() if seen is None else seen
 parts=[text]
 for path in references(text):
  if path in seen: continue
  seen.add(path); source=path.read_text(errors='ignore'); parts.extend(helper_text(source,seen))
 return parts
def python_needs(text):
 sources=helper_text(text); combined='\n'.join(sources)
 if 'python3' not in combined: return None
 if re.search(r'(^|\n)\s*(?:import|from) yaml\b',combined): return 'yaml'
 if re.search(r'(^|\n)\s*import websocket\b',combined): return 'smoke'
 if re.search(r'(^|[;&|\s])(ruff|yamllint)(?:\s|$)',combined): return 'ci'
 if re.search(r'(^|[;&|\s])(?:python3 -m )?mkdocs(?:\s|$)',combined): return 'docs'
 return 'none'
def has_gh_consumer(text): return any(run_has_gh(source) for source in helper_text(text))

workflow_paths=sorted((*workflows.glob('*.yml'), *workflows.glob('*.yaml')))

expected_permissions={
 'weekly-security-scan.yml':{'contents':'read'},
 'manual-backup-restore.yml':{'contents':'read'},
 'release-notes.yml':{'contents':'write'},
}
for name, permissions in expected_permissions.items():
 workflow=load(workflows/name)
 if workflow.get('permissions') != permissions: fail(f'{name} must define exact top-level permissions: {permissions}')

license_workflow=load(workflows/'license-scan.yml')
license_changes=(license_workflow.get('jobs') or {}).get('changes')
if not isinstance(license_changes,dict): fail('license-scan.yml changes job is missing')
license_filter_steps=[
 step for step in license_changes.get('steps',[])
 if isinstance(step,dict) and step.get('id')=='filter'
 and str(step.get('uses','')).startswith('dorny/paths-filter@')
]
if len(license_filter_steps)!=1: fail('license-scan.yml must define exactly one dorny paths filter step')
license_filter_with=license_filter_steps[0].get('with')
if not isinstance(license_filter_with,dict): fail('license-scan.yml dorny paths filter must define with mapping')
license_filter=license_filter_with.get('filters')
if not isinstance(license_filter,str): fail('license-scan.yml dorny paths filter must define filters YAML')
license_filter_data=yaml.safe_load(license_filter)
if not isinstance(license_filter_data,dict): fail('license-scan.yml dorny filters YAML must be a mapping')
for group in ('gradle','npm'):
 paths=license_filter_data.get(group)
 if not isinstance(paths,list): fail(f'license-scan.yml {group} filter must be a path list')
 for authority_path in ('.node-version','config/workflow-tool-versions.env'):
  if paths.count(authority_path)!=1: fail(f'license-scan.yml {group} filter must contain exactly one {authority_path}')

node_count=python_count=gh_count=0
for path in workflow_paths:
 for job_name,job in (load(path).get('jobs') or {}).items():
  if not isinstance(job,dict): continue
  checkout=py=gh=loader=False; python_profile=None
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
    py=True; python_profile=step.get('with',{}).get('requirements','none')
    if python_profile not in {'none','yaml','ci','smoke','docs'}: fail(f'{path.name}:{job_name}: invalid Python dependency profile')
   if uses=='./.github/actions/resolve-certificate-identity-mode':
    py=True; python_profile='yaml'
   if uses=='./.github/actions/setup-gh':
    gh_count+=1
    if not checkout: fail(f'{path.name}:{job_name}: gh setup before checkout')
    gh=True
   need=python_needs(run)
   if need is not None and not py: fail(f'{path.name}:{job_name}: direct or helper Python consumer uses ambient runner Python')
   if need=='yaml' and python_profile not in {'yaml','ci'}: fail(f'{path.name}:{job_name}: PyYAML helper lacks its pinned dependency profile')
   if need in {'smoke','ci','docs'} and python_profile!=need: fail(f'{path.name}:{job_name}: {need} helper lacks its pinned dependency profile')
   if has_gh_consumer(run) and not gh: fail(f'{path.name}:{job_name}: direct or helper gh consumer is not preceded by canonical setup-gh')
   if uses.startswith('oss-review-toolkit/ort-ci-github-action@'):
    if not loader or step.get('with',{}).get('image')!='${{ steps.workflow-tool-versions.outputs.ort-image }}': fail(f'{path.name}:{job_name}: ORT bypasses authority')
if not min(node_count,python_count,gh_count): fail('expected Node, Python, and gh consumers')

publisher=load(workflows/'publish-pr-runtime-images.yml')['jobs']['publish']
if publisher.get('permissions',{}).get('contents')!='read': fail('trusted publisher checkout requires contents: read')

for path in sorted(actions.glob('*/action.yml')):
 setup_py=setup_gh=False
 for step in load(path).get('runs',{}).get('steps',[]):
  uses=str(step.get('uses','')); run=str(step.get('run',''))
  setup_py |= uses=='./.github/actions/setup-python' or (path.parent.name=='setup-python' and uses.startswith('actions/setup-python@'))
  setup_gh |= uses=='./.github/actions/setup-gh'
  if uses.startswith('actions/setup-python@') and path.parent.name!='setup-python': fail(f'{path}: bypasses setup-python wrapper')
  if 'python3' in run and not setup_py: fail(f'{path}: Python consumer lacks setup')
  if run_has_gh(run) and not setup_gh: fail(f'{path}: gh consumer lacks setup')

text='\n'.join(p.read_text() for p in workflow_paths)
for forbidden in ('python-version:','ruff==','PyYAML\n','websocket-client\n','aquasecurity/trivy/main','zaproxy:stable','VELERO_VERSION=v','KUBECONFORM_VERSION="v','BUF_VERSION: \'1.30.0\''):
 if forbidden in text: fail(f'workflow contains stale or duplicated authority: {forbidden}')
required={
 'ci.yml':('buf-version','buf-linux-x86-64-sha256','kubeconform-version','kubeconform-linux-amd64-sha256','actionlint-version'),
 'docs.yml':('lychee-version',), 'manual-backup-restore.yml':('velero-version','velero-linux-amd64-sha256'),
 'security.yml':('trivy-version',), 'weekly-security-scan.yml':('trivy-version',), 'zap-baseline.yml':('zap-image',)}
for name,needles in required.items():
 data=(workflows/name).read_text()
 if any(n not in data for n in needles): fail(f'{name} does not consume all canonical tool outputs')

ci_text=(workflows/'ci.yml').read_text()
buf_curl_pattern=(
 r'(?m)^\s*curl -fsSL --retry 3 --retry-delay 2 --retry-max-time 30 \\\n'
 r'\s*--connect-timeout 10 --max-time 60 \\\n'
 r'\s*"https://github\.com/bufbuild/buf/releases/download/v\$\{BUF_VERSION\}/buf-Linux-x86_64" \\\n'
 r'\s*-o /tmp/buf$')
if len(re.findall(buf_curl_pattern,ci_text)) != 1:
 fail('ci.yml must define exactly one Buf installer with canonical bounded retries and timeouts')
for required in (
 'BUF_VERSION: ${{ steps.workflow-tool-versions.outputs.buf-version }}',
 'BUF_SHA256: ${{ steps.workflow-tool-versions.outputs.buf-linux-x86-64-sha256 }}',
 'echo "${BUF_SHA256}  /tmp/buf" | sha256sum --check --status',
):
 if required not in ci_text: fail(f'ci.yml Buf installer does not consume canonical authority: {required}')

kubeconform_curl_pattern=(
 r'(?m)^\s*curl -fsSL --retry 3 --retry-delay 2 --retry-max-time 30 --connect-timeout 10 --max-time 60 '
 r'"https://github\.com/yannh/kubeconform/releases/download/v\$\{KUBECONFORM_VERSION\}/kubeconform-linux-amd64\.tar\.gz" '
 r'-o /tmp/kubeconform\.tgz$')
if len(re.findall(kubeconform_curl_pattern,ci_text)) != 1:
 fail('ci.yml must define exactly one kubeconform installer with canonical bounded retries and timeouts')
for required in (
 'KUBECONFORM_VERSION: ${{ steps.workflow-tool-versions.outputs.kubeconform-version }}',
 'KUBECONFORM_SHA256: ${{ steps.workflow-tool-versions.outputs.kubeconform-linux-amd64-sha256 }}',
 'echo "${KUBECONFORM_SHA256}  /tmp/kubeconform.tgz" | sha256sum --check --status',
):
 if required not in ci_text: fail(f'ci.yml kubeconform installer does not consume canonical authority: {required}')

setup_gh_text=(actions/'setup-gh/action.yml').read_text()
setup_gh_curl_pattern=(
 r'(?m)^\s*curl -fsSL --retry 3 --retry-delay 2 --retry-max-time 30 \\\n'
 r'\s*--connect-timeout 10 --max-time 60 \\\n'
 r'\s*"https://github\.com/cli/cli/releases/download/v\$\{gh_version\}/gh_\$\{gh_version\}_linux_amd64\.tar\.gz" \\\n'
 r'\s*-o "\$temporary_archive"$')
if len(re.findall(setup_gh_curl_pattern,setup_gh_text)) != 1:
 fail('setup-gh must define exactly one installer with canonical bounded retries and timeouts')
for required in (
 'GH_VERSION: ${{ steps.versions.outputs.gh-version }}',
 'GH_SHA256: ${{ steps.versions.outputs.gh-linux-amd64-sha256 }}',
 'printf \'%s  %s\\n\' "$gh_sha256" "$temporary_archive"',
 'printf \'%s  %s\\n\' "$gh_sha256" "$archive_path"',
):
 if required not in setup_gh_text: fail(f'setup-gh installer does not consume canonical authority: {required}')

ci_jobs=load(workflows/'ci.yml').get('jobs') or {}
frontend_checks=ci_jobs.get('frontend-checks')
if not isinstance(frontend_checks,dict): fail('ci.yml frontend-checks job is missing')
frontend_steps=frontend_checks.get('steps')
if not isinstance(frontend_steps,list): fail('ci.yml frontend-checks steps are missing')
cache_lockfiles={'cache-openapi':'config/openapi/package-lock.json','cache-web-client':'web-client/package-lock.json'}
for cache_id,lockfile in cache_lockfiles.items():
 matches=[step for step in frontend_steps if isinstance(step,dict) and step.get('id')==cache_id]
 if len(matches)!=1: fail(f'ci.yml frontend-checks must define exactly one {cache_id} step')
 with_data=matches[0].get('with')
 key=with_data.get('key') if isinstance(with_data,dict) else None
 hash_match=re.search(r'hashFiles\(([^)]*)\)',key) if isinstance(key,str) else None
 paths=re.findall(r"['\"]([^'\"]+)['\"]",hash_match.group(1)) if hash_match else []
 if set(paths)!={'.node-version',lockfile}: fail(f'ci.yml {cache_id} key must hash .node-version and {lockfile}')

velero_manifest=(root/'k8s/velero/verify-backups-cronjob.yaml').read_text()
velero_images=re.findall(r'image: velero/velero:[^\s]+',velero_manifest)
allowed_velero_images={
 f'image: velero/velero:v{a["VELERO_VERSION"]}',
 f'image: velero/velero:v{a["VELERO_VERSION"]}@{a["VELERO_IMAGE_DIGEST"]}',
}
if len(velero_images)!=1 or velero_images[0] not in allowed_velero_images:
 fail('Velero image version/digest projection is stale')

renovate=json.loads((root/'renovate.json').read_text())
if not {'nodenv','pyenv','pip_requirements','custom.regex'} <= set(renovate['enabledManagers']): fail('Renovate managers incomplete')
if len(renovate.get('customManagers',[]))!=2: fail('Renovate must define version and image authority managers')
expected_dep_names={
 'KUBECTL':'kubernetes/kubernetes',
 'HELM':'helm/helm',
 'GH':'cli/cli',
 'BUF':'bufbuild/buf',
 'KUBECONFORM':'yannh/kubeconform',
 'VELERO':'vmware-tanzu/velero',
 'ACTIONLINT':'rhysd/actionlint',
 'TRIVY':'aquasecurity/trivy',
 'LYCHEE':'lycheeverse/lychee',
}
expected_image_dep_names={
 'VELERO':'velero/velero',
 'ORT':'ghcr.io/oss-review-toolkit/ort',
 'ZAP':'ghcr.io/zaproxy/zaproxy',
}
expected=Counter((expected_dep_names[x],a[f'{x}_VERSION']) for x in expected_dep_names)
expected.update((expected_image_dep_names[x],a[f'{x}_VERSION']) for x in expected_image_dep_names)
matched=Counter()
for manager_index,manager in enumerate(renovate['customManagers']):
 patterns=manager.get('matchStrings') if isinstance(manager,dict) else None
 if not isinstance(patterns,list) or not patterns: fail(f'Renovate custom manager {manager_index} must define a non-empty matchStrings list')
 for pattern_index,pattern_source in enumerate(patterns):
  if not isinstance(pattern_source,str) or not pattern_source: fail(f'Renovate custom manager {manager_index} matchStrings[{pattern_index}] must be a non-empty string')
  try:
   pattern=re.compile(pattern_source.replace('(?<','(?P<'))
  except re.error as error:
   fail(f'Renovate custom manager {manager_index} matchStrings[{pattern_index}] is invalid: {error}')
  try:
   matched.update((m.group('depName'),m.group('currentValue')) for m in pattern.finditer(ap.read_text()))
  except (IndexError,KeyError) as error:
   fail(f'Renovate custom manager {manager_index} matchStrings[{pattern_index}] lacks required capture groups: {error}')
if matched != expected: fail('Renovate does not discover every workflow tool authority exactly once')
rules=renovate.get('packageRules',[])
runtime_major_rule=next((rule for rule in rules if rule.get('description')=='Keep canonical Node and Python runtime majors'),None)
if runtime_major_rule != {
 'description':'Keep canonical Node and Python runtime majors',
 'matchManagers':['nodenv','pyenv'],
 'matchUpdateTypes':['major'],
 'enabled':False,
}:
 fail('Renovate must disable only major Node and Python runtime authority updates')
infra_rule=next((rule for rule in rules if rule.get('groupName')=='infrastructure non-major updates'),None)
velero_rule=next((rule for rule in rules if rule.get('description')=='Production Velero image changes require promotion evidence'),None)
if infra_rule is None or velero_rule is None or rules.index(velero_rule)<=rules.index(infra_rule):
 fail('production Velero Renovate exception must follow infrastructure automerge')
if velero_rule.get('matchManagers')!=['kubernetes'] \
 or velero_rule.get('matchFileNames')!=['k8s/velero/verify-backups-cronjob.yaml'] \
 or velero_rule.get('matchPackageNames')!=['velero/velero'] \
 or velero_rule.get('pinDigests') is not False \
 or velero_rule.get('automerge') is not False \
 or velero_rule.get('enabled') is not False:
 fail('production Velero Renovate exception must disable automated digest projection and merge')

proto=(root/'gradle/proto-convention.gradle').read_text()
if 'protobuf-gradle-plugin 0.10.0' in proto or 'libs.plugins.protobuf' not in proto or '${gradle.gradleVersion}' not in proto: fail('protobuf diagnostic must derive both canonical versions')
for action in ('setup-gh','setup-helm'):
 data=(actions/action/'action.yml').read_text()
 if 'sha256sum --check --status' not in data: fail(f'{action} must verify its archive')
if 'RUNNER_OS' not in (actions/'setup-gh/action.yml').read_text() or 'RUNNER_ARCH' not in (actions/'setup-gh/action.yml').read_text(): fail('setup-gh must reject unsupported platforms')
lychee=(root/'dev-tools/docs/link-check.sh').read_text()
for required in ('source "$ROOT_DIR/config/workflow-tool-versions.env"','/lychee/${LYCHEE_VERSION}','LYCHEE_LINUX_X86_64_MUSL_SHA256','sha256sum --check --status','VERIFIED_MARKER','ARCHIVE=','marker_binary_sha','extracted_sha','mv -f "$staging/lychee" "$BIN"','mv -f "$staged_archive" "$ARCHIVE"'):
 if required not in lychee: fail(f'local Lychee installer does not consume its authority: {required}')
docs=(workflows/'docs.yml').read_text()
if 'lycheeverse/lychee-action@' in docs or 'run: bash ./dev-tools/docs/link-check.sh' not in docs: fail('docs workflow must use the checksum-verifying Lychee installer')
for identity in ('outputs.lychee-version','outputs.lychee-linux-x86-64-musl-sha256'):
 if identity not in docs: fail(f'docs Lychee cache omits tool identity: {identity}')
setup_python=load(actions/'setup-python/action.yml')
if set(setup_python.get('inputs',{}))!={'requirements'}: fail('setup-python must expose one canonical requirements profile input')
setup_source=(actions/'setup-python/action.yml').read_text()
for profile,path in {'yaml':'config/python/yaml-requirements.txt','ci':'config/python/ci-requirements.txt','smoke':'config/python/smoke-requirements.txt','docs':'config/docs/requirements.txt'}.items():
 if f'{profile}) requirements_file={path}' not in setup_source: fail(f'setup-python does not own {profile} requirements')
print('Workflow version authority contract passed')
PY
