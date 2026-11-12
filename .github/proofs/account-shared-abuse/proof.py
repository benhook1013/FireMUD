"""Finite isolated hosted witness. No publication, checkout edits, or deployed configuration."""
import collections, hashlib, json, os, pathlib, re, selectors, shutil, signal, subprocess, sys, time
import xml.etree.ElementTree as ET
HERE=pathlib.Path(__file__).resolve().parent
ROOT=pathlib.Path(os.environ['SOURCE_DIRECTORY']).resolve()
OUT=pathlib.Path(os.environ['PROOF_DIR']).resolve()
EXPECTED=json.loads((HERE/'expected.json').read_text())
RUN=os.environ['GITHUB_RUN_ID']; LABEL='firemud.proof.run='+RUN
MAX_LOG=8*1024**2; MAX_OUTPUT=24*1024**2

def sha(data):return hashlib.sha256(data).hexdigest()
def command(args,timeout=15):return subprocess.check_output(args,cwd=ROOT,timeout=timeout).decode().strip()
def git(*args):return command(['git',*args])
def save(name,obj): (OUT/name).write_text(json.dumps(obj,indent=2)+'\n')
def readlines(name):return [json.loads(x) for x in (OUT/name).read_text().splitlines()]
def filepin(path):return {'path':str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),'bytes':path.stat().st_size,'sha256':sha(path.read_bytes())}
def owned():return command(['docker','ps','-aq','--filter','label=firemud.proof=account-shared-abuse-fixtures','--filter','label='+LABEL]).split()
def event_check():
    assert os.environ['GITHUB_REPOSITORY']=='benhook1013/FireMUD'
    assert os.environ['GITHUB_EVENT_NAME']=='push'
    assert os.environ['GITHUB_REF']=='refs/heads/dot/proof-account-shared-abuse-fixtures-20261112'
    event=json.loads(pathlib.Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    assert event['ref']==os.environ['GITHUB_REF'] and not event.get('deleted',False)
    assert event['after']==os.environ['GITHUB_SHA']
    assert event['repository']['full_name']=='benhook1013/FireMUD'
    return {'event':'push','ref':event['ref'],'workflow_sha':event['after'],'repository':event['repository']['full_name']}
def source_check():
    event_check()
    expected_sha=os.environ['SOURCE_SHA'];assert re.fullmatch('[0-9a-f]{40}',expected_sha)
    assert expected_sha==EXPECTED['source_commit']
    assert git('rev-parse','HEAD')==expected_sha
    assert git('rev-parse','HEAD^{tree}')==EXPECTED['candidate_tree']
    assert git('rev-parse',EXPECTED['base_head']+'^{tree}')==EXPECTED['base_tree']
    assert git('show','-s','--format=%P','HEAD')==EXPECTED['base_head']
    assert git('diff-tree','--no-commit-id','--name-only','-r',EXPECTED['base_head'],'HEAD').splitlines()==sorted(EXPECTED['changed_paths'])
    assert not git('status','--porcelain','--untracked-files=no')
    for row in EXPECTED['files']:
        data=(ROOT/row['path']).read_bytes();assert sha(data)==row['sha256']
        assert git('rev-parse','HEAD:'+row['path'])==row['after_blob']
        if row['before_blob']:assert git('rev-parse',EXPECTED['base_head']+':'+row['path'])==row['before_blob']
    assert git('rev-parse',EXPECTED['cumulative_base_head']+'^{tree}')==EXPECTED['cumulative_base_tree']
    assert git('diff-tree','--no-commit-id','--name-only','-r',EXPECTED['cumulative_base_head'],'HEAD').splitlines()==sorted(r['path'] for r in EXPECTED['cumulative_files'])
    for row in EXPECTED['cumulative_files']:
        assert git('rev-parse','HEAD:'+row['path'])==row['after_blob']
        if row['before_blob']:
            assert git('rev-parse',EXPECTED['cumulative_base_head']+':'+row['path'])==row['before_blob']
    return {'commit':expected_sha,'tree':git('rev-parse','HEAD^{tree}'),'base':EXPECTED['base_head'],'cumulative_base':EXPECTED['cumulative_base_head']}
def verify():
    OUT.mkdir(exist_ok=False)
    src=source_check()
    assert not list(ROOT.glob('services/*/build')), 'Fresh checkout must contain no prior service outputs'
    assert not (ROOT/'buildSrc/build').exists()
    assert not owned(), 'No pre-existing proof-labelled containers permitted'
    assert shutil.disk_usage(ROOT).free>=12*1024**3,'Hosted disk admission failed'
    assert mem_available()>=5*1024**3,'Hosted memory admission failed'
    assert command(['docker','info','--format','{{.ServerVersion}}'])
    save('source.json',src)
    save('event.json',event_check())
    tracked=git('ls-files').splitlines()
    inputs=[filepin(ROOT/x) for x in tracked if x.endswith(('.proto','.sql','.gradle','.gradle.kts','.toml')) or x in ['gradle/wrapper/gradle-wrapper.properties','gradle/wrapper/gradle-wrapper.jar','services/common-test-support/src/testFixtures/resources/net/firedevops/firemud/test/container-images.properties']]
    save('generator-build-migration-inputs.json',inputs)
    save('tool-versions.json',{'java':subprocess.run(['java','-version'],capture_output=True,text=True).stderr,'docker':command(['docker','version','--format','{{json .Server}}'])})
def mem_available():return int(next(x.split()[1] for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')))*1024
def java_rss():
    result=0
    for p in pathlib.Path('/proc').glob('[0-9]*'):
        try:
            if (p/'comm').read_text().strip() in ['java','javac']:
                result+=int(next(x.split()[1] for x in (p/'status').read_text().splitlines() if x.startswith('VmRSS:')))*1024
        except (OSError,StopIteration):pass
    return result

def run(stage):
    source_check(); assert stage in EXPECTED['methods']
    before=shutil.disk_usage(ROOT).free;start=time.monotonic();peak=0;failure=None;observed={};written=0
    args=['bash','dev-tools/validation/run-gradle-with-dependency-recovery.sh','--runner','./gradlew','--','--no-daemon','--no-build-cache','--no-configuration-cache','--no-scan','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8','-Pkotlin.compiler.execution.strategy=in-process','--console=plain','-I',str(HERE/'proof.init.gradle'),':account-service:integrationTest']
    if stage=='receiving-fixtures':args.insert(args.index('--max-workers=1'),'--rerun-tasks')
    for name in EXPECTED['methods'][stage]:args+=['--tests',name]
    save(stage+'-command.json',args)
    env=dict(os.environ,PROOF_STAGE=stage)
    proc=subprocess.Popen(args,cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
    sel=selectors.DefaultSelector();sel.register(proc.stdout,selectors.EVENT_READ);last_inspect=0
    try:
        with (OUT/(stage+'-gradle.log')).open('wb') as log:
            while sel.get_map():
                elapsed=time.monotonic()-start;peak=max(peak,java_rss())
                if elapsed> (1200 if stage=='receiving-fixtures' else 600):failure='time-budget'
                if peak>6*1024**3 or mem_available()<256*1024**2:failure='memory-budget'
                if shutil.disk_usage(ROOT).free<2*1024**3 or before-shutil.disk_usage(ROOT).free>10*1024**3:failure='disk-budget'
                if sum(f.stat().st_size for f in OUT.rglob('*') if f.is_file())>MAX_OUTPUT:failure='artifact-budget'
                if elapsed-last_inspect>1:
                    last_inspect=elapsed
                    for ident in owned():
                        if ident not in observed:
                            raw=json.loads(command(['docker','inspect',ident]))[0];host=raw['HostConfig']
                            observed[ident]={'id':ident,'image':raw['Config']['Image'],'memory':host['Memory'],'memory_swap':host['MemorySwap'],'pids_limit':host['PidsLimit'],'labels':{k:v for k,v in raw['Config']['Labels'].items() if k.startswith('firemud.proof')}}
                            if host['Memory'] not in [256*1024**2,512*1024**2] or host['MemorySwap']!=host['Memory']:failure='unbounded-owned-container'
                if failure:
                    try:os.killpg(proc.pid,signal.SIGTERM)
                    except ProcessLookupError:pass
                for key,_ in sel.select(.1):
                    data=os.read(key.fileobj.fileno(),65536)
                    if not data:sel.unregister(key.fileobj);continue
                    if written+len(data)>MAX_LOG:failure='log-budget';data=data[:max(0,MAX_LOG-written)]
                    log.write(data);written+=len(data);log.flush()
                if failure and proc.poll() is None:
                    try:proc.wait(timeout=10)
                    except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGKILL)
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid,signal.SIGTERM)
            try:proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid,signal.SIGKILL);proc.wait()
    code=proc.wait();save(stage+'-execution.json',{'exit':code,'failure':failure,'elapsed_seconds':time.monotonic()-start,'peak_java_rss':peak,'log_bytes':written,'containers':list(observed.values())})
    result_dir=ROOT/'services/account-service/build/test-results/integrationTest';dest=OUT/(stage+'-junit');dest.mkdir()
    if result_dir.exists():
        for f in result_dir.glob('*.xml'):shutil.copyfile(f,dest/f.name)
    save(stage+'-generated.json',[filepin(f) for pattern in ['services/*/build/generated*/**/*.java','services/*/build/tmp/jooq/config.xml'] for f in ROOT.glob(pattern) if f.is_file()])
    classpath=[]
    cpfile=OUT/(stage+'-classpath.txt')
    if cpfile.exists():
        for line in cpfile.read_text().splitlines():
            path=pathlib.Path(line)
            if path.is_file():classpath.append(filepin(path))
            elif path.is_dir():
                digest=hashlib.sha256();count=0;size=0
                for entry in sorted(x for x in path.rglob('*') if x.is_file()):
                    data=entry.read_bytes();count+=1;size+=len(data)
                    digest.update(str(entry.relative_to(path)).encode()+b'\0'+sha(data).encode()+b'\n')
                classpath.append({'path':str(path),'files':count,'bytes':size,'inventory_sha256':digest.hexdigest()})
            else:raise RuntimeError('Runtime classpath missing: '+line)
    save(stage+'-classpath-inventory.json',classpath)
    source_check()
    if code or failure:raise SystemExit(code or 1)

def collect():
    issues=[];results={}
    try:
        cleanup_result=json.loads((OUT/'cleanup.json').read_text());assert not cleanup_result['remaining'] and not cleanup_result['errors']
    except Exception as e:issues.append('Teardown: '+repr(e))
    try:source_check()
    except Exception as e:issues.append('Source verification: '+repr(e))
    for stage,expected in EXPECTED['methods'].items():
        try:
            execution=json.loads((OUT/(stage+'-execution.json')).read_text());assert execution['exit']==0 and execution['failure'] is None
            assert len(execution['containers']) >= 4, 'Missing physical container observation'
            wanted=EXPECTED['invocations'][stage];events=readlines(stage+'-events.jsonl');starts=[];finishes=[]
            for e in events:
                if e['event'] in ['start','finish']:
                    name=e['className']+'.'+e['name'].removesuffix('()');(starts if e['event']=='start' else finishes).append(name)
                    if e['event']=='finish':assert e['result']=='SUCCESS',e
            assert collections.Counter(starts)==collections.Counter(wanted)==collections.Counter(finishes)
            junit=[]
            for f in (OUT/(stage+'-junit')).glob('*.xml'):
                doc=ET.parse(f).getroot()
                for test in doc.iter('testcase'):
                    assert not any(test.find(x) is not None for x in ['failure','error','skipped'])
                    junit.append(test.attrib['classname']+'.'+test.attrib['name'].removesuffix('()'))
            assert collections.Counter(junit)==collections.Counter(wanted)
            tasks=readlines(stage+'-tasks.jsonl');assert sum(t['event']=='test-task-start' for t in tasks)==1;assert sum(t['event']=='test-task-finish' for t in tasks)==1
            if stage=='receiving-fixtures':
                taskmap={t['path']:t['outcome'] for t in tasks if t['event']=='task'}
                for name in [':account-service:generateJooq',':account-service:generateProto',':account-service:compileJava',':account-service:compileTestFixturesJava',':account-service:compileIntegrationTestJava',':account-service:integrationTest']:
                    assert taskmap.get(name)=='EXECUTED',(name,taskmap.get(name))
            generated=json.loads((OUT/(stage+'-generated.json')).read_text());assert any(x['path'].endswith('/AccountsRecord.java') for x in generated);assert any(x['path'].endswith('/AuthLoginRequest.java') for x in generated)
            results[stage]={'passed':len(wanted),'methods':sorted(wanted),'execution':execution}
        except Exception as e:issues.append(stage+': '+repr(e))
    save('summary.json',{'source':os.environ['SOURCE_SHA'],'base':EXPECTED['base_head'],'passed':not issues,'issues':issues,'results':results,'limits':'Controlled receiving/transaction fixtures only; not deployed trust, platform policy delivery or production activation.'})
    pins=[filepin(f) for f in OUT.rglob('*') if f.is_file() and f.name!='checksums.json'];save('checksums.json',pins)
    assert sum(f.stat().st_size for f in OUT.rglob('*') if f.is_file())<=MAX_OUTPUT
    print((OUT/'summary.json').read_text());raise SystemExit(1 if issues else 0)
def cleanup():
    OUT.mkdir(exist_ok=True)
    ids=owned();errors=[]
    for ident in ids:
        try:subprocess.run(['docker','rm','-fv',ident],check=True,timeout=20,stdout=subprocess.DEVNULL)
        except Exception as e:errors.append(str(e))
    remaining=owned();save('cleanup.json',{'scoped_run':RUN,'removed':ids,'remaining':remaining,'errors':errors})
    if remaining or errors:raise SystemExit(1)
if __name__=='__main__':
    action=sys.argv[1]
    if action=='verify':verify()
    elif action=='run':run(sys.argv[2])
    elif action=='collect':collect()
    elif action=='cleanup':cleanup()
    elif action=='package':
        destination=pathlib.Path(str(OUT)+'-upload');destination.mkdir(exist_ok=False)
        files=[f for f in OUT.rglob('*') if f.is_file()] if OUT.exists() else []
        size=sum(f.stat().st_size for f in files)
        if files and size<=MAX_OUTPUT:
            for f in files:
                target=destination/f.relative_to(OUT);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,target)
        else:
            (destination/'artifact-blocker.json').write_text(json.dumps({'passed':False,'reason':'Missing or over-budget proof output','bytes':size,'limit':MAX_OUTPUT,'files':[filepin(f) for f in files]},indent=2)+'\n')
            raise SystemExit(1)
    else:raise SystemExit('Unknown action')
