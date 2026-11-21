import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { resolveImage, verifyConfig, releaseEnvironment, verifyCandidate, verifyPromotion } from './release.mjs';

const pins = JSON.parse(readFileSync(new URL('../deploy/secret-pins.json', import.meta.url)));
const questionnaire = JSON.parse(readFileSync(new URL('../deploy/questionnaire-env.json', import.meta.url)));
const sha = 'a'.repeat(40), index = `sha256:${'1'.repeat(64)}`, child = `sha256:${'2'.repeat(64)}`;
const image = 'asia-south1-docker.pkg.dev/project-4343c1b3-d768-4b8f-bff/talentbridge/talentbridge-backend';
const revision = 'talentbridge-backend-gaaaaaaaa-123-1', tag = 'gaaaaaaaa-123-1';
const aliases = (project = '1089736501943') => Object.values(pins)
  .map(({ secret }) => `${secret}:projects/${project}/secrets/${secret}`).join(',');
const manifest = { schemaVersion: 2, mediaType: 'application/vnd.oci.image.index.v1+json', manifests: [
  { mediaType: 'application/vnd.oci.image.manifest.v1+json', digest: child, platform: { os: 'linux', architecture: 'amd64' } },
  { mediaType: 'application/vnd.oci.image.manifest.v1+json', digest: `sha256:${'3'.repeat(64)}`, platform: { os: 'unknown', architecture: 'unknown' } },
] };
const template = () => ({ metadata: { annotations: {
  'run.googleapis.com/cpu-throttling': 'true', 'run.googleapis.com/secrets': aliases(),
  'run.googleapis.com/startup-cpu-boost': 'true',
  'autoscaling.knative.dev/minScale': '0', 'autoscaling.knative.dev/maxScale': '1',
} }, spec: {
  serviceAccountName: 'talentbridge-runtime@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com',
  containerConcurrency: 40, timeoutSeconds: 120,
  containers: [{ image: `${image}@${index}`, ports: [{ containerPort: 8080 }],
    resources: { limits: { cpu: '1000m', memory: '1Gi' } },
    startupProbe: { httpGet: { path: '/api/health/startup', port: 8080 }, periodSeconds: 5, timeoutSeconds: 5, failureThreshold: 48 },
    env: [{ name: 'APP_SEED_RESET_DEMO_DATA', value: 'false' }, { name: 'FRONTEND_URL', value: 'https://example.test' },
      { name: 'APP_DEMO_MODE', value: 'true' },
      ...Object.entries(pins).map(([name, pin]) => ({ name, valueFrom: { secretKeyRef: { name: pin.secret, key: pin.version } } }))],
  }],
} });
const ready = { observedGeneration: 1, conditions: [{ type: 'Ready', status: 'True' }] };
const service = () => ({ metadata: { generation: 1, annotations: {
  'run.googleapis.com/minScale': '0', 'run.googleapis.com/maxScale': '1',
} }, spec: { template: template(), traffic: [{ revisionName: 'previous', percent: 100 }] },
  status: { ...structuredClone(ready), traffic: [{ revisionName: 'previous', percent: 100 }] } });
const candidate = () => {
  const value = { ...template(), metadata: { ...template().metadata, name: revision, generation: 1, labels: { 'commit-sha': sha } },
    status: { ...structuredClone(ready), imageDigest: `${image}@${child}` } };
  value.spec.containers[0].env.push(...structuredClone(questionnaire));
  return value;
};
const tagged = () => {
  const value = service();
  value.spec.template.metadata.name = revision;
  value.spec.template.metadata.labels = { 'commit-sha': sha };
  value.spec.template.spec.containers[0].env.push(...structuredClone(questionnaire));
  value.spec.traffic.push({ revisionName: revision, tag });
  value.status.traffic.push({ revisionName: revision, tag, url: 'https://candidate---service.a.run.app' });
  return value;
};
const legacyService = () => {
  const value = service();
  delete value.metadata.annotations['run.googleapis.com/minScale'];
  delete value.spec.template.metadata.annotations['autoscaling.knative.dev/minScale'];
  value.spec.template.metadata.annotations['autoscaling.knative.dev/maxScale'] = '20';
  value.spec.template.spec.containers[0].resources.limits.cpu = '1';
  return value;
};
const promoted = () => {
  const value = tagged();
  value.spec.traffic[0] = { revisionName: revision, percent: 100 };
  value.status.traffic[0] = { revisionName: revision, percent: 100 };
  return value;
};
const rejects = (fn, code) => assert.throws(fn, error => error.message === code);
const options = { pins, sha, revision, tag, image, digests: [index, child], environment: releaseEnvironment(service(), questionnaire) };

function executeRelease(manifest, before, failCloudRead = false) {
  const entry = new URL('./release.mjs', import.meta.url);
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', `
    import commands from 'node:child_process';
    import fs from 'node:fs';
    import { syncBuiltinESMExports } from 'node:module';
    import { fileURLToPath } from 'node:url';
    const events = [], entry = new URL(${JSON.stringify(entry.href)});
    const read = fs.readFileSync;
    fs.readFileSync = (file, ...args) => {
      if (file instanceof URL && file.href === new URL('../deploy/questionnaire-env.json', entry).href) {
        events.push('manifest');
        return Buffer.from(${JSON.stringify(manifest)});
      }
      return read(file, ...args);
    };
    commands.execFileSync = (command, args) => {
      events.push([command, ...args]);
      if (command === 'git' && args.join(' ') === 'rev-parse HEAD') return ${JSON.stringify(sha)};
      if (command === 'gcloud' && args.slice(0, 4).join(' ') === 'run services describe talentbridge-backend'
          && !${JSON.stringify(failCloudRead)}) return ${JSON.stringify(JSON.stringify(before))};
      throw new Error('OFFLINE_COMMAND_BOUNDARY');
    };
    syncBuiltinESMExports();
    process.argv[1] = fileURLToPath(entry);
    process.on('beforeExit', () => console.log(JSON.stringify(events)));
    await import(entry.href);
  `], { encoding: 'utf8', timeout: 5000, env: {
    HOME: '/private/tmp', TMPDIR: '/private/tmp', RUNNER_TEMP: '/private/tmp',
    GITHUB_SHA: sha, GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '1',
    GITHUB_REF: 'refs/heads/main', GITHUB_EVENT_NAME: 'push',
  } });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 1);
  return { events: JSON.parse(result.stdout), error: result.stderr.trim() };
}

test('actual release entry rejects malformed local manifest before any cloud command', () => {
  const invalid = structuredClone(questionnaire);
  invalid.find(e => e.name === 'APP_QUESTIONNAIRE_ENABLED').value = 'TRUE';
  for (const [manifest, error] of [[JSON.stringify(invalid), 'QUESTIONNAIRE_CONFIG'], ['{', 'RELEASE_FAILED']]) {
    const result = executeRelease(manifest, service(), true);
    assert.equal(result.error, error, JSON.stringify(result.events));
    assert(result.events.includes('manifest'));
    assert.deepEqual(result.events.filter(e => Array.isArray(e) && e[0] !== 'git'), []);
  }
});

test('actual release entry with valid manifest retains metadata and baseline guards', () => {
  for (const [mutate, error, calls] of [
    [() => {}, 'GCLOUD_FAILED', 2],
    [s => s.spec.template.spec.serviceAccountName = 'wrong', 'RUNTIME_ACCOUNT', 1],
    [s => s.spec.template.spec.containers[0].env.find(e => e.name === 'RESEND_API_KEY').valueFrom.secretKeyRef.key = '1', 'SECRET_PIN', 1],
    [s => s.spec.template.spec.containers[0].env.push({ name: 'ASSESSMENT_TASKS_QUEUE', value: 'wrong' }), 'QUESTIONNAIRE_CONFIG', 1],
  ]) {
    const before = service(); mutate(before);
    const result = executeRelease(JSON.stringify(questionnaire), before);
    assert.equal(result.error, error);
    assert.equal(result.events.filter(e => e === 'manifest').length, 1);
    const cloud = result.events.filter(e => Array.isArray(e) && e[0] === 'gcloud');
    assert.equal(cloud.length, calls);
    assert.deepEqual(cloud[0].slice(0, 5), ['gcloud', 'run', 'services', 'describe', 'talentbridge-backend']);
    assert(result.events.indexOf('manifest') < result.events.indexOf(cloud[0]));
    if (calls === 2) assert.deepEqual(cloud[1], ['gcloud', 'auth', 'configure-docker', 'asia-south1-docker.pkg.dev', '--quiet']);
  }
});

test('initial reviewed questionnaire environment must pass candidate and promotion without unrelated changes', () => {
  assert.deepEqual(questionnaire.filter(e => e.name !== 'APP_QUESTIONNAIRE_ENABLED'), [
    { name: 'ASSESSMENT_TASKS_PROJECT', value: 'project-4343c1b3-d768-4b8f-bff' },
    { name: 'ASSESSMENT_TASKS_LOCATION', value: 'asia-south1' },
    { name: 'ASSESSMENT_TASKS_QUEUE', value: 'talentbridge-assessments' },
    { name: 'ASSESSMENT_TASKS_TARGET', value: 'https://talentbridge-backend-5665rby4oa-el.a.run.app' },
    { name: 'ASSESSMENT_TASKS_AUDIENCE', value: 'https://talentbridge-backend-5665rby4oa-el.a.run.app' },
    { name: 'ASSESSMENT_TASKS_CALLER_EMAIL', value: 'talentbridge-assessment-tasks@project-4343c1b3-d768-4b8f-bff.iam.gserviceaccount.com' },
  ]);
  assert(['false', 'true'].includes(questionnaire.find(e => e.name === 'APP_QUESTIONNAIRE_ENABLED').value));
  assert.equal(Object.keys(pins).length, 11);
  assert.equal(pins.RESEND_API_KEY.version, '2');
  for (const verify of [verifyCandidate, verifyPromotion]) {
    const current = verify === verifyCandidate ? tagged() : promoted(), actual = candidate();
    verify(current, actual, service(), options);
  }
});

for (const [previous, next] of [[undefined, 'false'], ['false', 'false'], ['false', 'true'], ['true', 'true']]) {
  test(`reviewed release preserves exact environment for ${previous ?? 'absent'} to ${next}`, () => {
    const before = service(), settings = structuredClone(questionnaire);
    settings.find(e => e.name === 'APP_QUESTIONNAIRE_ENABLED').value = next;
    if (previous !== undefined) {
      const existing = structuredClone(questionnaire);
      existing.find(e => e.name === 'APP_QUESTIONNAIRE_ENABLED').value = previous;
      before.spec.template.spec.containers[0].env.push(...existing);
    }
    before.spec.template.spec.containers[0].env.push({ name: 'EXISTING_SETTING', value: 'preserve-me' });
    const original = structuredClone(before), originalSettings = structuredClone(settings);
    const environment = releaseEnvironment(before, settings);
    assert.equal(environment.length, template().spec.containers[0].env.length + 8);
    assert.deepEqual(environment.filter(e => !settings.some(s => s.name === e.name)),
      before.spec.template.spec.containers[0].env.filter(e => !settings.some(s => s.name === e.name)));
    assert.equal(environment.find(e => e.name === 'APP_QUESTIONNAIRE_ENABLED').value, next);
    for (const verify of [verifyCandidate, verifyPromotion]) {
      const current = verify === verifyCandidate ? tagged() : promoted(), actual = candidate();
      current.spec.template.spec.containers[0].env = structuredClone(environment).reverse();
      actual.spec.containers[0].env = structuredClone(environment);
      verify(current, actual, before, { ...options, environment });
    }
    assert.deepEqual(before, original);
    assert.deepEqual(settings, originalSettings);
    environment[0].value = 'detached';
    assert.deepEqual(before, original);
  });
}

test('only the exact seven literal settings and two explicit flag values are accepted', () => {
  for (const invalid of [undefined, null, {}, 'false', [], questionnaire.slice(1), [...questionnaire, questionnaire[0]],
    questionnaire.map((e, i) => i === 1 ? questionnaire[0] : e),
    ...['APP_QUESTIONNAIRE_OTHER', 'ASSESSMENT_TASKS_OTHER', 'OTHER', 'OTHER_API_KEY', 'N8N_WEBHOOK_SECRET']
      .map(name => questionnaire.map((e, i) => i === 0 ? { name, value: 'DO_NOT_LOG_SENTINEL' } : e))]) {
    rejects(() => releaseEnvironment(service(), invalid), 'QUESTIONNAIRE_CONFIG');
  }
  for (const setting of questionnaire) {
    const values = setting.name === 'APP_QUESTIONNAIRE_ENABLED'
      ? [true, false, 0, 1, 'TRUE', 'False', ' false', 'true\n', '']
      : ['', null, false, 123, `${setting.value}/`, `${setting.value}\n`, `${setting.value},OTHER=value`, 'DO_NOT_LOG_SENTINEL'];
    if (setting.name === 'ASSESSMENT_TASKS_TARGET' || setting.name === 'ASSESSMENT_TASKS_AUDIENCE') {
      values.push(setting.value.replace('https:', 'http:'), `${setting.value}/internal/assessment-jobs`,
        `${setting.value}?q=1`, `${setting.value}#fragment`, 'https://unreviewed.run.app');
    }
    for (const value of values) {
      const invalid = questionnaire.map(e => e.name === setting.name ? { ...e, value } : e);
      rejects(() => releaseEnvironment(service(), invalid), 'QUESTIONNAIRE_CONFIG');
    }
    for (const entry of [null, { name: setting.name }, { ...setting, name: [setting.name] },
      { ...setting, name: { value: setting.name } }, { ...setting, extra: 'bad' },
      { name: setting.name, valueFrom: { secretKeyRef: { name: 'DO_NOT_LOG_SENTINEL', key: '1' } } },
      { ...setting, valueFrom: { secretKeyRef: { name: 'DO_NOT_LOG_SENTINEL', key: '1' } } }]) {
      rejects(() => releaseEnvironment(service(), questionnaire.map(e => e.name === setting.name ? entry : e)), 'QUESTIONNAIRE_CONFIG');
    }
  }
});

test('predeployment questionnaire drift and duplicate environment names fail before deployment', () => {
  for (const entry of [{ name: 'ASSESSMENT_TASKS_UNKNOWN', value: 'bad' },
    { name: 'APP_QUESTIONNAIRE_ENABLED', value: 'TRUE' },
    { name: 'ASSESSMENT_TASKS_QUEUE', value: 'other' },
    { name: 'ASSESSMENT_TASKS_TARGET', valueFrom: { secretKeyRef: { name: 'bad', key: '1' } } }]) {
    const before = service(); before.spec.template.spec.containers[0].env.push(entry);
    rejects(() => releaseEnvironment(before, questionnaire), 'QUESTIONNAIRE_CONFIG');
  }
  const before = service(); before.spec.template.spec.containers[0].env.push(before.spec.template.spec.containers[0].env[0]);
  rejects(() => releaseEnvironment(before, questionnaire), 'DUPLICATE_ENV');
});

test('candidate and promotion reject unrelated, questionnaire and secret drift in either actual environment', () => {
  for (const verify of [verifyCandidate, verifyPromotion]) {
    for (const target of ['template', 'revision']) {
      for (const [mutate, code] of [
        [env => env.push({ name: 'UNREVIEWED', value: 'value' }), 'CONFIG_DRIFT'],
        [env => env.find(e => e.name === 'FRONTEND_URL').value = 'https://changed.test', 'CONFIG_DRIFT'],
        [env => env.splice(env.findIndex(e => e.name === 'FRONTEND_URL'), 1), 'CONFIG_DRIFT'],
        [env => env.find(e => e.name === 'APP_DEMO_MODE').value = 'false', 'CONFIG_DRIFT'],
        [env => env.find(e => e.name === 'APP_QUESTIONNAIRE_ENABLED').value = questionnaire[0].value === 'false' ? 'true' : 'false', 'CONFIG_DRIFT'],
        [env => env.splice(env.findIndex(e => e.name === 'ASSESSMENT_TASKS_QUEUE'), 1), 'CONFIG_DRIFT'],
        [env => env.find(e => e.name === 'ASSESSMENT_TASKS_TARGET').value += '/internal/assessment-jobs', 'CONFIG_DRIFT'],
        [env => env.push({ name: 'OTHER_API_KEY', value: 'DO_NOT_LOG_SENTINEL' }), 'SENSITIVE_ENV'],
        [env => env.push(env[0]), 'DUPLICATE_ENV'],
        [env => env.find(e => e.name === 'RESEND_API_KEY').valueFrom.secretKeyRef.key = '1', 'SECRET_PIN'],
        [env => env.find(e => e.name === 'JWT_SECRET').valueFrom.secretKeyRef.name = 'other', 'SECRET_PIN'],
        [env => env.find(e => e.name === 'DB_PASSWORD').value = 'DO_NOT_LOG_SENTINEL', 'SECRET_PIN'],
        [env => env.splice(env.findIndex(e => e.name === 'N8N_WEBHOOK_SECRET'), 1), 'SECRET_PIN'],
      ]) {
        const current = verify === verifyCandidate ? tagged() : promoted(), actual = candidate();
        mutate(target === 'template' ? current.spec.template.spec.containers[0].env : actual.spec.containers[0].env);
        rejects(() => verify(current, actual, service(), options), code);
      }
    }
  }
});

test('legacy max-20 baseline passes unchanged preflight and permits an exact repaired candidate and promotion', () => {
  const before = legacyService(), original = structuredClone(before);
  verifyConfig(before.spec.template, pins);
  assert.equal(verifyCandidate(tagged(), candidate(), before, options), 'https://candidate---service.a.run.app');
  verifyPromotion(promoted(), candidate(), before, options);
  assert.deepEqual(before, original);
  for (const [verify, current] of [[verifyCandidate, tagged()], [verifyPromotion, promoted()]]) {
    current.spec.template.metadata.annotations['autoscaling.knative.dev/maxScale'] = '20';
    rejects(() => verify(current, candidate(), before, options), 'REVISION_SCALING');
  }
});

test('candidate and promotion reject warm service minima and missing or mismatched service maxima', () => {
  for (const verify of [verifyCandidate, verifyPromotion]) {
    for (const [key, values] of [
      ['run.googleapis.com/minScale', ['1', '20', '-1', '', 'default', 0, false]],
      ['run.googleapis.com/maxScale', ['20', '0', undefined, '', 'default', 1, false]],
    ]) {
      for (const value of values) {
        const current = verify === verifyCandidate ? tagged() : promoted();
        current.metadata.annotations[key] = value;
        rejects(() => verify(current, candidate(), legacyService(), options), 'SERVICE_SCALING');
      }
    }
    const current = verify === verifyCandidate ? tagged() : promoted();
    delete current.metadata.annotations;
    rejects(() => verify(current, candidate(), legacyService(), options), 'SERVICE_SCALING');
  }
});

test('candidate and promotion guard both template and actual revision scaling and explicit CPU boost', () => {
  for (const verify of [verifyCandidate, verifyPromotion]) {
    for (const target of ['template', 'revision']) {
      for (const [key, values, code] of [
        ['autoscaling.knative.dev/minScale', ['1', '20', '-1', '', 'default', 0, false], 'REVISION_SCALING'],
        ['autoscaling.knative.dev/maxScale', ['20', '0', undefined, '', 'default', 1, false], 'REVISION_SCALING'],
        ['run.googleapis.com/startup-cpu-boost', ['false', undefined, '', true], 'CPU_BOOST'],
      ]) {
        for (const value of values) {
          const current = verify === verifyCandidate ? tagged() : promoted(), actual = candidate();
          (target === 'template' ? current.spec.template : actual).metadata.annotations[key] = value;
          rejects(() => verify(current, actual, legacyService(), options), code);
        }
      }
    }
  }
});

test('absent service and revision minimum annotations mean zero, but maxima remain explicit', () => {
  for (const verify of [verifyCandidate, verifyPromotion]) {
    const current = verify === verifyCandidate ? tagged() : promoted(), actual = candidate();
    delete current.metadata.annotations['run.googleapis.com/minScale'];
    delete current.spec.template.metadata.annotations['autoscaling.knative.dev/minScale'];
    delete actual.metadata.annotations['autoscaling.knative.dev/minScale'];
    verify(current, actual, legacyService(), options);
  }
});

test('Stage 3 v1 metadata accepts exact aliases by known project number or ID without mutation', () => {
  for (const annotation of [aliases(), aliases('project-4343c1b3-d768-4b8f-bff'), aliases().split(',').reverse().join(',')]) {
    const value = template(); value.metadata.annotations['run.googleapis.com/secrets'] = annotation;
    const original = structuredClone(value);
    verifyConfig(value, pins);
    assert.deepEqual(value, original);
  }
});

test('secret aliases reject cross-project, cross-secret, extra, missing, duplicate and malformed mappings', () => {
  const valid = aliases().split(',');
  for (const annotation of [undefined, '', aliases('999999999999'), aliases('untrusted-project'),
    valid.slice(1).join(','), `${aliases()},extra:projects/1089736501943/secrets/extra`,
    [valid[0], ...valid.slice(0, -1)].join(','),
    [valid[0].replace('projects/1089736501943', 'projects/project-4343c1b3-d768-4b8f-bff'), ...valid.slice(0, -1)].join(','),
    aliases().replace('/secrets/talentbridge-db-username', '/secrets/talentbridge-db-password'),
    aliases().replace('talentbridge-db-username:', 'unexpected-alias:'),
    `${aliases()},`, aliases().replace(':projects/', 'projects/'), aliases().replace(':projects/', '::projects/'),
    `${aliases()}\n`, aliases().replace(',', ', '), { malformed: true }]) {
    const value = template(); value.metadata.annotations['run.googleapis.com/secrets'] = annotation;
    rejects(() => verifyConfig(value, pins), 'SECRET_ALIAS');
  }
});

test('OCI index and Docker list resolve only the unique linux/amd64 image child', () => {
  assert.deepEqual(resolveImage(index, index, manifest), [index, child]);
  assert.deepEqual(resolveImage(index, index, { ...manifest, mediaType: 'application/vnd.docker.distribution.manifest.list.v2+json' }), [index, child]);
  rejects(() => resolveImage(index, child, manifest), 'SOURCE_DIGEST');
  for (const mutate of [m => m.manifests.shift(), m => m.manifests.push(m.manifests[0]),
    m => m.manifests[0].platform.architecture = 'arm64', m => m.manifests[0].digest = 'sha256:bad',
    m => m.manifests[0].mediaType = 'application/vnd.oci.image.index.v1+json']) {
    const bad = structuredClone(manifest); mutate(bad);
    rejects(() => resolveImage(index, index, bad), 'AMD64_CHILD');
  }
});

test('single image manifests are not mistaken for indexes; unknown schemas fail closed', () => {
  assert.deepEqual(resolveImage(child, child, { schemaVersion: 2, mediaType: 'application/vnd.oci.image.manifest.v1+json' }), [child]);
  rejects(() => resolveImage(child, child, { schemaVersion: 1, mediaType: manifest.mediaType }), 'IMAGE_MANIFEST');
  rejects(() => resolveImage(child, child, { schemaVersion: 2, mediaType: 'text/html' }), 'IMAGE_MANIFEST');
});

test('all eleven exact numeric secret refs and safe non-secret config are mandatory', () => {
  verifyConfig(template(), pins);
  for (const mutate of [t => t.spec.containers[0].env.pop(),
    t => t.spec.containers[0].env.at(-1).valueFrom.secretKeyRef.key = 'latest',
    t => t.spec.containers[0].env.at(-1).valueFrom.secretKeyRef.name = 'wrong-secret',
    t => t.spec.containers[0].env.at(-1).value = 'DO_NOT_LOG_SENTINEL',
    t => t.spec.containers[0].env.push({ name: 'OTHER_API_KEY', value: 'DO_NOT_LOG_SENTINEL' }),
    t => t.spec.containers[0].env.push(t.spec.containers[0].env[0]),
    t => t.spec.containers[0].env[0].value = 'true',
    t => t.spec.containers[0].env.push({ name: 'PORT', value: '9000' }),
    t => t.metadata.annotations['run.googleapis.com/secrets'] = 'alias:projects/other/secrets/secret']) {
    const bad = template(); mutate(bad);
    assert.throws(() => verifyConfig(bad, pins), error => /^[A-Z_]+$/.test(error.message) && !error.message.includes('SENTINEL'));
  }
  rejects(() => verifyConfig(template(), { ...pins, DB_PASSWORD: { secret: 'secret', version: 'latest' } }), 'SECRET_MANIFEST');
});

test('runtime account, port, HTTP startup budget, and existing resources are guarded', () => {
  for (const mutate of [t => t.spec.serviceAccountName = 'wrong', t => t.spec.containers[0].ports[0].containerPort = 9000,
    t => t.spec.containers[0].startupProbe = { tcpSocket: { port: 8080 } },
    t => t.spec.containers[0].startupProbe.failureThreshold = 47,
    t => t.spec.containerConcurrency = 80, t => t.spec.timeoutSeconds = 60,
    t => t.spec.containers[0].resources.limits.cpu = '2',
    t => t.metadata.annotations['run.googleapis.com/cpu-throttling'] = 'false']) {
    const bad = template(); mutate(bad); assert.throws(() => verifyConfig(bad, pins));
  }
});

test('candidate verifies exact source, runtime child, tag, preserved config and zero traffic', () => {
  assert.equal(verifyCandidate(tagged(), candidate(), service(), options), 'https://candidate---service.a.run.app');
  for (const mutate of [r => r.metadata.name = 'wrong', r => r.metadata.labels['commit-sha'] = 'b'.repeat(40),
    r => r.spec.containers[0].image = `${image}:latest`, r => r.status.imageDigest = `${image}@sha256:${'9'.repeat(64)}`,
    r => r.status.imageDigest = `other-repo@${child}`, r => r.status.conditions[0].status = 'False']) {
    const bad = candidate(); mutate(bad); assert.throws(() => verifyCandidate(tagged(), bad, service(), options));
  }
  for (const mutate of [s => s.status.traffic[1].revisionName = 'wrong', s => s.status.traffic[1].percent = 1,
    s => s.status.traffic[1].url = 'http://insecure.test', s => s.status.traffic[0].revisionName = 'unexpected',
    s => s.spec.traffic[1].percent = 1,
    s => s.spec.template.spec.containers[0].env[1].value = 'https://changed.test',
    s => s.spec.template.spec.serviceAccountName = 'wrong']) {
    const bad = tagged(); mutate(bad); assert.throws(() => verifyCandidate(bad, candidate(), service(), options));
  }
});

test('v1 generation numbers or decimal strings must be reconciled before candidate use', () => {
  const current = tagged(), revisionValue = candidate();
  current.metadata.generation = '102'; current.status.observedGeneration = 102;
  revisionValue.metadata.generation = 1; revisionValue.status.observedGeneration = '1';
  assert.equal(verifyCandidate(current, revisionValue, service(), options), 'https://candidate---service.a.run.app');
  for (const mutate of [s => s.status.observedGeneration = '101', s => delete s.status.observedGeneration,
    s => delete s.metadata.generation, s => s.status.conditions[0].status = 'Unknown']) {
    const bad = structuredClone(current); mutate(bad);
    rejects(() => verifyCandidate(bad, revisionValue, service(), options), 'NOT_READY');
  }
  delete revisionValue.status.observedGeneration;
  rejects(() => verifyCandidate(current, revisionValue, service(), options), 'NOT_READY');
});

test('promotion requires reconciled explicit 100% traffic to the verified revision', () => {
  const promoted = tagged();
  promoted.spec.traffic[0] = { revisionName: revision, percent: 100 };
  promoted.status.traffic[0] = { revisionName: revision, percent: 100 };
  verifyPromotion(promoted, candidate(), service(), options);
  for (const mutate of [s => s.status.traffic[0].percent = 99, s => s.status.traffic[0].revisionName = 'old',
    s => s.spec.traffic[0] = { latestRevision: true, percent: 100 }, s => s.status.observedGeneration = 0,
    s => s.spec.template.spec.containers[0].env[0].value = 'true']) {
    const bad = structuredClone(promoted); mutate(bad);
    assert.throws(() => verifyPromotion(bad, candidate(), service(), options));
  }
  const bad = candidate(); bad.status.imageDigest = `${image}@sha256:${'9'.repeat(64)}`;
  assert.throws(() => verifyPromotion(promoted, bad, service(), options));
});
