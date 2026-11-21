# Assessment relay update

Use `docs/n8n/talentbridge-ai-relay.json` from the reviewed questionnaire worktree/commit, not the original checkout's older export.
This artifact is not published by the implementation worker.

1. Export/back up the existing live workflow before importing this artifact into a separate unpublished draft for comparison.
2. Reconcile the draft's node changes into the existing live workflow, retaining its workflow identity, POST webhook path `13b62449-1c68-4d84-9564-d0eefbf0a2e3`, header credential `tb1`, and OpenAI credential `OpenAi - HelloForce`.
   Do not create a second active webhook with the same path or replace credential values.
3. Keep the model expression wired to Prepare Input and the existing model credential.
   Assessment operations allow only `gpt-4o` and `gpt-4o-mini`, matching the backend evaluation-model setting.
4. Check the installed node implementations before publication: Agent 3.1 must support `options.maxIterations` and `options.enableStreaming`; Chat Model 1.2 must support `options.maxRetries` and `options.timeout`.
   For assessment input the evaluated settings must be one iteration, streaming false, zero model retries, and model timeout 50000ms for generation or 80000ms for evaluation.
   Preserve legacy values of ten iterations, streaming true, two retries, and 60000ms.
   Do not attach tools, memory, output parsers, fallback models, built-in tools, retry-on-fail, or an error workflow that retries assessment calls.
5. Disable raw execution-data retention, manual-execution retention, external LangChain tracing, and payload/debug logging in the deployed workflow/instance before assessment traffic.
   Never paste real briefs, code, answers, keys or model outputs into operator logs.
6. Run `node --test scripts/assessment-relay.test.mjs` locally against the export, review the diff, then manually publish the reconciled existing workflow.
   Do not execute either assessment operation during import/publication verification.
   The four approved synthetic live attempts remain reserved for Task5.

Backend generation requires at most4096 output tokens and64000 combined system/message UTF-16 units; evaluation requires8192 and96000.
Both operations reject oversize input, nonempty/missing history, unsupported models, wrong token caps, NUL and unpaired surrogates without calling the model.
They preserve the full system/message and return the untouched model string for strict backend JSON validation.
The evaluation operation carries either `assessmentKind=repository` or `assessmentKind=questionnaire` inside its message JSON.
Repository requests contain only approved brief/frozen evidence and return repository categories; separate private requests carry questions/rubrics/answers and return member question marks only.
Each is a separate model attempt and quota reservation, not a combined score/answer call.
Future workers must prepare/validate both required requests before making either call, persist their results separately, and join them only in backend scoring.
If no member completed all five answers, skip the private model request; Task4 alone applies incomplete-member zeroes.
Legacy chat, chat_guard and evaluation slicing/normalization remain unchanged.

The export's Code nodes and conditional expressions are executed in local tests, not in an installed n8n runtime.
No live/installed n8n version was inspected, so operator confirmation of supported settings and absence of instance-level retry/tracing overrides is required.
A backend reservation measures one authorized relay attempt, not proof of provider billing; a lost connection can leave provider completion uncertain.
Do not retry an uncertain live call manually outside the later durable job policy.

Official source checked on2026-11-21: [Chat Model versions including1.2, option definitions and LangChain wiring](https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/nodes-langchain/nodes/llms/LMChatOpenAi/LmChatOpenAi.node.ts), [Agent versions3/3.1](https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/nodes-langchain/nodes/agents/Agent/V3/AgentV3.node.ts), [shared Agent options](https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/nodes-langchain/nodes/agents/Agent/agents/ToolsAgent/options.ts), and [Agent3 streaming option](https://github.com/n8n-io/n8n/blob/master/packages/%40n8n/nodes-langchain/nodes/agents/Agent/agents/ToolsAgent/V3/description.ts).
