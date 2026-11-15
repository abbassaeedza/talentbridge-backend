import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const workflow = readFileSync(new URL('../.github/workflows/deploy-cloud-run.yml', import.meta.url), 'utf8');

test('deployment explicitly sets both scaling levels, startup boost, and the preserved resource envelope', () => {
  const release = readFileSync(new URL('./release.mjs', import.meta.url), 'utf8');
  const deploy = release.match(/gcloud\(\['run', 'deploy',[\s\S]*?\]\);/)?.[0];
  assert(deploy);
  const flags = [...deploy.matchAll(/'(--[^']+)'/g)].map(match => match[1]);
  for (const flag of ['--no-traffic', '--cpu=1', '--memory=1Gi', '--min=0', '--max=1',
    '--min-instances=0', '--max-instances=1', '--cpu-boost', '--cpu-throttling',
    '--concurrency=40', '--timeout=120', '--port=8080',
    '--startup-probe=httpGet.path=/api/health/startup,httpGet.port=8080,periodSeconds=5,timeoutSeconds=5,failureThreshold=48']) {
    assert(flags.includes(flag), `Missing deployment flag: ${flag}`);
  }
});

test('PR and main checks are separate, synthetic, and cannot request OIDC', () => {
  assert.match(workflow, /pull_request:\n\s+branches: \[main\]/);
  const checks = workflow.slice(0, workflow.indexOf('\n  deploy:'));
  assert.match(checks, /name: Backend unit tests/);
  assert.match(checks, /name: Backend PostgreSQL tests/);
  assert.match(checks, /image: postgres:16/);
  assert.match(checks, /TALENTBRIDGE_TEST_DB_URL: jdbc:postgresql:\/\/127\.0\.0\.1:5432\/talentbridge_test/);
  assert.match(checks, /bash \.\/mvnw -B -Pdatabase-tests verify/);
  assert.match(checks, /node-version: '24'/);
  assert.doesNotMatch(checks, /id-token|secrets\.|google-github-actions/);
});

test('deployment is main-only, serialized, verified, and stale-checked before OIDC', () => {
  const deploy = workflow.slice(workflow.indexOf('\n  deploy:'));
  assert.match(deploy, /name: Deploy production/);
  assert.match(deploy, /needs: \[unit, postgres\]/);
  assert.match(deploy, /github\.ref == 'refs\/heads\/main'/);
  assert.match(deploy, /github\.event_name == 'push'/);
  assert.match(deploy, /github\.event_name == 'workflow_dispatch'/);
  assert.match(deploy, /group: talentbridge-backend-production\n\s+cancel-in-progress: false/);
  assert.match(deploy, /permissions:\n\s+contents: read\n\s+id-token: write/);
  assert.match(deploy, /ref: \$\{\{ github\.sha \}\}/);
  const firstStep = deploy.slice(deploy.indexOf('steps:'));
  assert.match(firstStep, /steps:\n\s+- name: Skip superseded source/);
  assert(firstStep.indexOf('refs/heads/main') < firstStep.indexOf('google-github-actions/auth@v3'));
  assert.equal((deploy.match(/if: steps\.source\.outputs\.current == 'true'/g) ?? []).length, 6);
  assert.doesNotMatch(workflow, /upload-artifact|secrets versions access|credentials_json/);
});

test('the first deployment step skips stale commits and fails closed on lookup errors', t => {
  const script = workflow.match(/id: source[\s\S]*?run: \|\n((?: {10}.*\n)+)/)?.[1].replace(/^ {10}/gm, '');
  assert(script);
  const directory = mkdtempSync(join(tmpdir(), 'release-step-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  let sequence = 0;
  const execute = (main, result = '0') => {
    const output = join(directory, String(sequence++));
    const run = spawnSync('bash', ['-e', '-o', 'pipefail', '-c',
      `gh() { printf '%s\\n' "$MOCK_MAIN_SHA"; return "$MOCK_RESULT"; }\n${script}`], {
      encoding: 'utf8', env: { ...process.env, MOCK_MAIN_SHA: main, MOCK_RESULT: result,
        GITHUB_SHA: 'a'.repeat(40), GITHUB_REPOSITORY: 'synthetic/repo', GITHUB_OUTPUT: output },
    });
    return { ...run, githubOutput: existsSync(output) ? readFileSync(output, 'utf8') : '' };
  };
  const current = execute('a'.repeat(40));
  assert.equal(current.status, 0); assert.equal(current.githubOutput, 'current=true\n');
  const stale = execute('b'.repeat(40));
  assert.equal(stale.status, 0); assert.equal(stale.githubOutput, 'current=false\n');
  assert.equal(stale.stdout, 'SUPERSEDED_SOURCE_SKIPPED\n');
  const failed = execute('', '1');
  assert.equal(failed.status, 1); assert.equal(failed.stdout, 'MAIN_LOOKUP_FAILED\n');
  const invalid = execute('not-a-sha');
  assert.equal(invalid.status, 1); assert.equal(invalid.stdout, 'MAIN_SHA_INVALID\n');
});

test('Docker context excludes local and OIDC credentials', () => {
  const ignored = readFileSync(new URL('../.dockerignore', import.meta.url), 'utf8').trim().split('\n');
  for (const entry of ['.git', '.env', '.env.*', 'target', 'uploads', '.worktrees', '.superpowers', 'gha-creds-*.json']) {
    assert(ignored.includes(entry));
  }
  assert(!ignored.includes('src') && !ignored.includes('pom.xml'));
});
