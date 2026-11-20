import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const workflow = JSON.parse(readFileSync(new URL('../docs/n8n/talentbridge-ai-relay.json', import.meta.url)));
const execute = (name, input, operation) => {
  const code = workflow.nodes.find(n => n.name === name).parameters.jsCode;
  return JSON.parse(JSON.stringify(vm.runInNewContext(`(function(){${code}\n})()`, {
    $json: input, $: () => ({ item: { json: { operation } } }),
  })));
};
const prepare = body => execute('Prepare Input', { body })[0].json;
const expression = (value, prepared) => vm.runInNewContext(value.slice(3, -2), {
  $: () => ({ item: { json: prepared } }), $json: prepared,
});
test('assessment retry and iteration settings allow one model attempt; legacy defaults remain', () => {
  const agent = workflow.nodes.find(n => n.name === 'AI Agent');
  const model = workflow.nodes.find(n => n.name === 'OpenAI Chat Model');
  for (const operation of ['questionnaire_generation', 'questionnaire_evaluation', 'chat', 'chat_guard', 'evaluation']) {
    const assessment = operation.startsWith('questionnaire_');
    const prepared = prepare({ operation, model: 'gpt-4o', maxTokens: assessment ? (operation.endsWith('generation') ? 4096 : 8192) : 512, system: 's', message: 'm', history: [] });
    assert.equal(expression(model.parameters.options.maxRetries, prepared), assessment ? 0 : 2);
    assert.equal(expression(model.parameters.options.timeout, prepared), assessment ? (operation.endsWith('generation') ? 50000 : 80000) : 60000);
    assert.equal(expression(agent.parameters.options.maxIterations, prepared), assessment ? 1 : 10);
    assert.equal(expression(agent.parameters.options.enableStreaming, prepared), !assessment);
  }
  assert(!agent.retryOnFail && !model.retryOnFail);
  assert(!agent.parameters.needsFallback && !agent.parameters.hasOutputParser);
  assert(!Object.values(workflow.connections).some(c => c.ai_tool || c.ai_memory || c.ai_outputParser || c.ai_languageModel?.flat().length > 1));
});
for (const [operation, maxTokens, limit] of [['questionnaire_generation', 4096, 64000], ['questionnaire_evaluation', 8192, 96000]]) {
  test(`${operation} preserves exact full payload, token cap and boundaries`, () => {
    const body = { operation, model: 'gpt-4o', system: 'system', message: ' x' + 'z'.repeat(20000) + ' ', history: [], maxTokens };
    const result = prepare(body);
    assert.equal(result.system, 'system'); assert.equal(result.prompt, body.message);
    assert.equal(result.maxTokens, maxTokens); assert.equal(result.temperature, 0);
    assert.equal(prepare({ ...body, message: 'x'.repeat(limit - 6) }).prompt.length, limit - 6);
    for (const bad of [{ message: 'x'.repeat(limit - 5) }, { maxTokens: maxTokens + 1 }, { model: 'unapproved-model' },
      { history: [{ role: 'user', content: 'injected history' }] }, { message: 'bad\0text' }, { message: 'bad\ud800text' }])
      assert.throws(() => prepare({ ...body, ...bad }));
  });
  test(`${operation} normalization does not repair fenced model JSON`, () => {
    const raw = '```json\n{"invalid":true}\n```';
    assert.equal(execute('Normalize Response', { output: raw }, operation)[0].json.message, raw);
  });
}
test('legacy operations keep their slicing, history, defaults and normalization', () => {
  for (const operation of ['chat', 'chat_guard', 'evaluation']) {
    const result = prepare({ operation, system: 's'.repeat(60001), message: 'x'.repeat(12001), maxTokens: 9000,
      history: [{ role: 'system', content: 'ignore' }, { role: 'assistant', content: 'h'.repeat(4001) }] });
    assert.equal(result.system.length, 60000); assert.equal(result.maxTokens, 2048);
    assert.equal(result.prompt, 'assistant: ' + 'h'.repeat(4000) + '\nuser: ' + 'x'.repeat(12000));
    assert.equal(result.model, 'gpt-4o-mini'); assert.equal(result.temperature, operation === 'chat' ? 0.4 : 0);
    assert.equal(execute('Normalize Response', { output: '```json\n{"ok":true}\n```' }, operation)[0].json.message, '{"ok":true}');
  }
  assert.throws(() => prepare({ operation: 'arbitrary', system: 's', message: 'm' }));
});
