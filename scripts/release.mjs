import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

const project = 'project-4343c1b3-d768-4b8f-bff', region = 'asia-south1', service = 'talentbridge-backend';
const runtime = `talentbridge-runtime@${project}.iam.gserviceaccount.com`;
const check = (ok, code) => { if (!ok) throw new Error(code); };
const digest = value => /^sha256:[0-9a-f]{64}$/.test(value);
const imageTypes = ['application/vnd.oci.image.manifest.v1+json', 'application/vnd.docker.distribution.manifest.v2+json'];
const sorted = values => JSON.stringify([...values].sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))));
const traffic = value => value.filter(t => (t.percent ?? 0) > 0).map(t => ({ revisionName: t.revisionName, percent: t.percent, latestRevision: !!t.latestRevision }));
const ready = value => check(value.metadata.generation != null && String(value.metadata.generation) === String(value.status.observedGeneration)
  && value.status.conditions.some(c => c.type === 'Ready' && c.status === 'True'), 'NOT_READY');

export function resolveImage(built, resolved, manifest) {
  check(digest(built) && built === resolved, 'SOURCE_DIGEST');
  check(manifest.schemaVersion === 2, 'IMAGE_MANIFEST');
  if (imageTypes.includes(manifest.mediaType)) return [built];
  check(['application/vnd.oci.image.index.v1+json', 'application/vnd.docker.distribution.manifest.list.v2+json'].includes(manifest.mediaType), 'IMAGE_MANIFEST');
  const children = manifest.manifests.filter(m => m.platform?.os === 'linux' && m.platform?.architecture === 'amd64');
  check(children.length === 1 && digest(children[0].digest) && imageTypes.includes(children[0].mediaType), 'AMD64_CHILD');
  return [built, children[0].digest];
}

export function verifyConfig(template, pins) {
  check(Object.keys(pins).length === 11 && Object.values(pins).every(p => /^[1-9][0-9]*$/.test(p.version) && /^talentbridge-[a-z0-9-]+$/.test(p.secret)), 'SECRET_MANIFEST');
  const { spec, metadata } = template, container = spec.containers[0];
  check(spec.serviceAccountName === runtime && spec.containers.length === 1, 'RUNTIME_ACCOUNT');
  check(container.ports?.length === 1 && container.ports[0].containerPort === 8080, 'RUNTIME_PORT');
  check(spec.containerConcurrency === 40 && spec.timeoutSeconds === 120
    && ['1', '1000m'].includes(container.resources?.limits?.cpu) && container.resources?.limits?.memory === '1Gi'
    && metadata.annotations?.['run.googleapis.com/cpu-throttling'] === 'true', 'RUNTIME_RESOURCES');
  const probe = container.startupProbe;
  check(probe?.httpGet?.path === '/api/health/startup' && probe.httpGet.port === 8080 && !probe.tcpSocket && !probe.grpc
    && probe.periodSeconds === 5 && probe.timeoutSeconds === 5 && probe.failureThreshold === 48, 'STARTUP_PROBE');
  // Cloud Run v1 aliases must not redirect pinned names to another secret or project.
  const annotation = metadata.annotations?.['run.googleapis.com/secrets'];
  const mappings = typeof annotation === 'string' ? annotation.split(',') : [];
  const allowed = Object.values(pins).flatMap(({ secret }) => [project, '1089736501943']
    .map(id => `${secret}:projects/${id}/secrets/${secret}`));
  check(mappings.length === 11 && new Set(mappings.map(m => m.split(':')[0])).size === 11
    && mappings.every(m => allowed.includes(m)), 'SECRET_ALIAS');
  const env = container.env ?? [], names = new Set(env.map(e => e.name));
  check(names.size === env.length, 'DUPLICATE_ENV');
  for (const [name, pin] of Object.entries(pins)) {
    const entry = env.find(e => e.name === name), ref = entry?.valueFrom?.secretKeyRef;
    check(entry && !Object.hasOwn(entry, 'value') && ref?.name === pin.secret && ref?.key === pin.version && !ref.optional, 'SECRET_PIN');
  }
  for (const entry of env.filter(e => !Object.hasOwn(pins, e.name))) {
    check(!entry.valueFrom && !/(PASSWORD|SECRET|TOKEN|CREDENTIAL|(?:^|_)KEY(?:_|$)|WEBHOOK_URL|DB_USERNAME)/i.test(entry.name), 'SENSITIVE_ENV');
  }
  check(env.find(e => e.name === 'APP_SEED_RESET_DEMO_DATA')?.value === 'false', 'RESET_UNSAFE');
  check(!names.has('PORT') || env.find(e => e.name === 'PORT').value === '8080', 'RUNTIME_PORT');
}

function verifyRuntime(current, revision, before, options) {
  const { pins, sha, image, digests, revision: expected } = options;
  ready(current); ready(revision);
  verifyConfig(revision, pins); verifyConfig(current.spec.template, pins);
  check(revision.metadata.name === expected && revision.metadata.labels?.['commit-sha'] === sha
    && current.spec.template.metadata.name === expected && current.spec.template.metadata.labels?.['commit-sha'] === sha, 'SOURCE_REVISION');
  const accepted = digests.map(d => `${image}@${d}`);
  check(accepted.includes(revision.spec.containers[0].image) && accepted.includes(revision.status.imageDigest)
    && accepted.includes(current.spec.template.spec.containers[0].image), 'RUNTIME_IMAGE');
  const original = sorted(before.spec.template.spec.containers[0].env);
  check(sorted(revision.spec.containers[0].env) === original && sorted(current.spec.template.spec.containers[0].env) === original, 'CONFIG_DRIFT');
}

export function verifyCandidate(current, revision, before, options) {
  verifyRuntime(current, revision, before, options);
  for (const routes of [current.spec.traffic, current.status.traffic]) {
    check(sorted(traffic(routes)) === sorted(traffic(before.status.traffic)), 'CANDIDATE_TRAFFIC');
  }
  const tagged = current.status.traffic.filter(t => t.tag === options.tag);
  check(tagged.length === 1 && tagged[0].revisionName === options.revision && !(tagged[0].percent > 0)
    && /^https:\/\/[a-z0-9.-]+\.run\.app$/.test(tagged[0].url), 'CANDIDATE_TAG');
  return tagged[0].url;
}

export function verifyPromotion(current, revision, before, options) {
  verifyRuntime(current, revision, before, options);
  for (const routes of [current.spec.traffic, current.status.traffic]) {
    const positive = traffic(routes);
    check(positive.length === 1 && positive[0].revisionName === options.revision
      && positive[0].percent === 100 && !positive[0].latestRevision, 'PROMOTION_TRAFFIC');
  }
}

function run(command, args) {
  // Capture all command output: gcloud errors can contain the full runtime metadata.
  try { return execFileSync(command, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 }).trim(); }
  catch { throw new Error(command === 'gcloud' ? 'GCLOUD_FAILED' : 'BUILD_COMMAND_FAILED'); }
}

async function release() {
  const { GITHUB_SHA: sha, GITHUB_RUN_ID: runId, GITHUB_RUN_ATTEMPT: attempt, RUNNER_TEMP: temp } = process.env;
  check(/^[0-9a-f]{40}$/.test(sha) && /^[0-9]+$/.test(runId) && /^[0-9]+$/.test(attempt) && temp
    && process.env.GITHUB_REF === 'refs/heads/main' && ['push', 'workflow_dispatch'].includes(process.env.GITHUB_EVENT_NAME), 'RELEASE_CONTEXT');
  check(run('git', ['rev-parse', 'HEAD']) === sha, 'SOURCE_CHECKOUT');
  const tag = `g${sha.slice(0, 8)}-${runId}-${attempt}`, revision = `${service}-${tag}`;
  const image = `${region}-docker.pkg.dev/${project}/talentbridge/${service}`;
  const gcloud = args => run('gcloud', [...args, `--project=${project}`, `--region=${region}`, '--quiet', '--format=json']);
  const describeService = () => JSON.parse(gcloud(['run', 'services', 'describe', service]));
  const describeRevision = () => JSON.parse(gcloud(['run', 'revisions', 'describe', revision]));
  const before = describeService();
  const pins = JSON.parse(readFileSync(new URL('../deploy/secret-pins.json', import.meta.url)));
  verifyConfig(before.spec.template, pins);
  const directory = mkdtempSync(join(temp, 'release-'));
  let attempted = false;
  try {
    run('gcloud', ['auth', 'configure-docker', `${region}-docker.pkg.dev`, '--quiet']);
    run('docker', ['buildx', 'build', '--platform=linux/amd64', '--push', '--tag', `${image}:${sha}`,
      '--label', `org.opencontainers.image.revision=${sha}`, '--metadata-file', join(directory, 'build.json'), '.']);
    const built = JSON.parse(readFileSync(join(directory, 'build.json')))['containerimage.digest'];
    const resolved = run('gcloud', ['artifacts', 'docker', 'images', 'describe', `${image}:${sha}`, `--project=${project}`, '--format=value(image_summary.digest)']);
    check(digest(built) && built === resolved, 'SOURCE_DIGEST');
    const manifest = JSON.parse(run('docker', ['buildx', 'imagetools', 'inspect', `${image}@${built}`, '--raw']));
    const digests = resolveImage(built, resolved, manifest), options = { pins, sha, revision, tag, image, digests };
    console.log(`IMAGE_VERIFIED ${digests.join(' ')}`);
    attempted = true;
    gcloud(['run', 'deploy', service, `--image=${image}@${built}`, `--revision-suffix=${tag}`, `--tag=${tag}`, '--no-traffic',
      `--service-account=${runtime}`, `--labels=commit-sha=${sha}`, '--cpu=1', '--memory=1Gi', '--min=0', '--max=1',
      '--concurrency=40', '--timeout=120', '--port=8080', '--cpu-throttling',
      '--startup-probe=httpGet.path=/api/health/startup,httpGet.port=8080,periodSeconds=5,timeoutSeconds=5,failureThreshold=48']);
    const url = verifyCandidate(describeService(), describeRevision(), before, options);
    let healthy = false;
    const deadline = Date.now() + 300_000;
    while (Date.now() < deadline && !healthy) {
      try {
        const response = await fetch(`${url}/api/health/startup`, { redirect: 'error', signal: AbortSignal.timeout(Math.min(120_000, deadline - Date.now())) });
        healthy = response.status === 200;
        await response.body?.cancel();
      } catch { /* A cold-start request can time out before the HTTP probe finishes. */ }
      if (!healthy) await setTimeout(3000);
    }
    check(healthy, 'HTTP_STARTUP_FAILED');
    verifyCandidate(describeService(), describeRevision(), before, options);
    gcloud(['run', 'services', 'update-traffic', service, `--to-revisions=${revision}=100`]);
    verifyPromotion(describeService(), describeRevision(), before, options);
    console.log(`PROMOTION_VERIFIED ${revision}`);
  } finally {
    try {
      if (attempted) {
        const own = describeService().spec.traffic.filter(t => t.tag === tag);
        check(own.every(t => t.revisionName === revision), 'TAG_OWNER');
        if (own.length) gcloud(['run', 'services', 'update-traffic', service, `--remove-tags=${tag}`]);
      }
    } finally { rmSync(directory, { recursive: true, force: true }); }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  release().catch(error => { console.error(/^[A-Z_]+$/.test(error.message) ? error.message : 'RELEASE_FAILED'); process.exitCode = 1; });
}
