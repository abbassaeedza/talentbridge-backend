# TalentBridge n8n AI Relay Design

## Goal

TalentBridge needs a temporary path for chatbot and repository evaluation requests when the local backend IP is not permitted by the OpenAI project allowlist.
An existing remote n8n server has an allowed outbound IP and will relay both operations to OpenAI.

## Scope

The relay covers the existing chatbot and repository evaluation flows.
It does not change controllers, frontend request formats, evaluation parsing, prompts, model selection, or persisted data.
The existing direct OpenAI integration remains available when the relay is not configured.

## Selected Approach

TalentBridge will call one authenticated n8n production webhook for both operations.
Each request includes an `operation` value of `chat` or `evaluation`.
The n8n workflow switches on that value, calls OpenAI with its existing credential, and returns one common response shape.

This approach was selected over separate webhooks because the transport, authentication, timeout, and response handling are identical.
It was selected over emulating the OpenAI Chat Completions API because a small relay contract is easier to configure and test.

## Configuration

The backend will support these environment variables:

```text
N8N_AI_WEBHOOK_URL=
N8N_WEBHOOK_SECRET=
```

When `N8N_AI_WEBHOOK_URL` is blank, TalentBridge uses the existing direct OpenAI path.
When `N8N_AI_WEBHOOK_URL` is configured, both chatbot and evaluation calls use n8n.
If a webhook URL is configured without a secret, requests fail closed and no unauthenticated webhook call is made.
The backend will not fall back to direct OpenAI after an n8n failure because fallback could duplicate a charge or repeat a long-running evaluation.

The production webhook URL and secret belong in the untracked backend `.env` file and deployment secrets.
The real values must not be committed.

## Relay Request Contract

TalentBridge sends an HTTP `POST` request with `Content-Type: application/json` and the configured authentication header.

```text
X-TB-Secret: <configured secret>
```

The JSON body has this shape:

```json
{
  "operation": "chat",
  "model": "gpt-4o-mini",
  "system": "system instructions",
  "message": "user prompt",
  "history": [
    {
      "role": "user",
      "content": "previous message"
    }
  ],
  "maxTokens": 2048
}
```

For evaluation requests, `operation` is `evaluation`, `history` is an empty array, and `message` contains the existing evaluation prompt and repository content.
The relay preserves the existing chat and evaluation model configuration rather than hard-coding model names in n8n.

## Relay Response Contract

Successful workflow executions return HTTP `200` with JSON in this shape:

```json
{
  "message": "generated response"
}
```

For chatbot calls, `message` contains normal assistant text.
For evaluation calls, `message` contains the existing raw JSON string expected by the evaluation service.
TalentBridge accepts only a nonblank textual `message` value.

## n8n Workflow

The existing Webhook node uses `POST`, its production URL, and Header Auth.
The Header Auth credential validates `X-TB-Secret` before workflow execution.
A Switch node routes on `operation` and rejects unknown values.
Each route builds the OpenAI messages from `system`, `history`, and `message`, uses the provided `model` and `maxTokens`, and executes through the n8n OpenAI credential.
Both routes normalize the assistant output to the common `message` response field.
A Respond to Webhook node returns the normalized JSON after OpenAI completes.

The reverse proxy and n8n webhook body limits must be large enough for the repository content already accepted by TalentBridge evaluations.

## Error Handling

TalentBridge preserves its current safe client response of HTTP `503` with `AI service is temporarily unavailable` when n8n times out, returns a non-2xx response, or returns malformed JSON.
Backend logs include the relay HTTP status but never log the webhook secret, OpenAI key, complete prompt, repository content, or response body.
Interrupted requests restore the Java thread interrupt flag.

The n8n workflow should return non-2xx status codes for authentication, validation, and OpenAI failures instead of wrapping errors in a successful response.

## Testing

Backend service tests will use a local HTTP server as the relay boundary.
Tests will verify the chat payload, evaluation payload, authentication header, nonblank response validation, upstream error mapping, timeout behavior where practical, and direct OpenAI behavior when the webhook URL is absent.
The complete backend test suite will run after the focused relay tests.

A manual smoke test will call the configured production webhook through TalentBridge and confirm one chatbot response and one parseable evaluation response without exposing credentials in command output.

## Rollback

Removing `N8N_AI_WEBHOOK_URL` and restarting the backend restores direct OpenAI calls.
No database or frontend rollback is required.
