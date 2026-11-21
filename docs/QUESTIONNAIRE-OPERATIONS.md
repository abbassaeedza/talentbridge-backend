# Questionnaire operations

## Student and grading contract

Only newly accepted final submissions enroll when `APP_QUESTIONNAIRE_ENABLED=true`.
Legacy submissions and published grades remain unchanged.
Acceptance freezes the approved brief, repository commit/evidence and every participant, including the leader.
Every frozen member receives five distinct project-specific questions.
All member question sets publish atomically, starting one shared real 48-hour UTC window.
Answers remain editable for the full window, even after all five are complete.
At the exact deadline, the latest successfully saved complete set locks; incomplete members receive questionnaire zero.
Technical generation, delivery or grading failures remain pending, never a fabricated zero.

Team questionnaire Q is the arithmetic mean of all frozen member marks, including incomplete zeroes.
Repository score uses authenticity20%, quality25%, functionality25%, scope20% and collaboration10%.
Combined team score is75% repository plus25% Q; existing bounded individual contribution adjustments still apply.
Authenticity is uncertain evidence, not proof of misconduct.
Coordinator mark changes retain the original AI mark, effective mark, reason, actor and time.
Scorecards and grade notifications publish only at atomic coordinator finalization, after which marks are immutable.
Questions, answers, rubrics and private grading feedback must not enter shared reports, chatbot context or operational logs.

## Protected rollout and activation

The required [deployment manifest](../deploy/questionnaire-env.json) contains exactly seven non-secret settings.
Its six queue/OIDC values are fixed; only the explicit string feature flag can be `false` or `true`.
Target and audience are the canonical Cloud Run service origin without a trailing slash or worker path.
`AssessmentTaskQueue` appends `/internal/assessment-jobs/{jobId}` itself.
There is no process-environment override or fallback flag in the release helper.
Every release compares both the actual revision and service template against the predeployment environment plus only the reviewed manifest entries.
Unknown/malformed settings, unrelated drift and changed/removed secret references fail closed.

1. Release the backend through its normal protected main workflow with the committed flag `false`.
   Preserve all eleven numeric secret pins, including Resend2, demo mode, resetfalse, runtime identity, startup probe and min0/max1, CPU1, memory1Gi, concurrency40, timeout120.
   Verify the saved draft version response before releasing the frontend that requires it.
2. Release and verify the compatible frontend through its separate protected checks and Vercel deployment.
3. Verify the independently provisioned queue/IAM chain, disabled-backend authenticated delivery and bounded synthetic relay contract before activation.
   The user confirms n8n is all set; leave its published workflow and settings unchanged.
   This confirmation is not independent runtime/provider proof.
4. Activate only through a later reviewed protected commit changing the manifest flag to `true`.
   Keep that value committed so subsequent ordinary releases preserve `true` rather than silently resetting enrollment.
   Disabling new enrollment also requires an explicit protected manifest change and does not disable existing assignments or recovery.

Never deploy local source, bypass required checks, rotate/read secrets, add a twelfth secret or replace the environment with `--set-env-vars`/clear flags.
The release helper retains source-SHA/digest checks, no-traffic candidate verification and promotion of exactly that revision to100%.
[Cloud Run documents](https://docs.cloud.google.com/run/docs/configuring/services/environment-variables) why `--update-env-vars` preserves unrelated entries.

## Queue, authentication and recovery

Use only `talentbridge-assessments` in `asia-south1`, with max concurrent dispatch1 and no always-on worker.
The runtime enqueues using ADC and queue-scoped enqueue permission; it can act as the dedicated same-project assessment caller.
Cloud Tasks' service agent mints the caller's Google OIDC token, and the caller invokes only the backend service.
Do not grant the caller queue administration/enqueue rights or create a service-account key.
Application worker authentication checks Google signature, issuer, audience, expiry and exact verified caller identity.
Ordinary application JWTs and forged task headers must fail, even though the Cloud Run service is public.

Before any model attempt, an authorized operator can prove actual authenticated delivery with a fresh synthetic task whose URL UUID differs from its body UUID.
Only a400 after authentication is success for this negative probe;401 is not.
Confirm zero prior dispatches before manually running that exact task, retain safe status-only evidence, delete only that probe and restore normal queue delivery.
Never purge the queue, force an active task or use an existing job ID for a probe.

Persisted job leases, dispatch generations and completed steps make duplicate delivery/restart recovery idempotent.
Use the existing coordinator generation/evaluation retry controls for visible pending failures, not direct database edits or quota resets.
Separate assessment quota defaults to50 actual attempts per Karachi day and three automatic actual model attempts per job; quota exhaustion defers until the next day.
An uncertain relay response can still consume an attempt; do not refund it or manually replay a model call.

All four specifically approved synthetic live attempts remain reserved: two member generations, one repository assessment and one private grading call, with no retry allowance.
The later authorized live check must pause the queue only around single known task dispatches and restore normal delivery during the actual48-hour answer window.
Stop if a failed/uncertain call would make the remaining flow exceed consent.
Local injected-clock tests are not proof of production expiry; retain the real48-hour deadline and arrange its follow-up before claiming production grading completion.
