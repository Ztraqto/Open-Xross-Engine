# Contributing to OpenXrossEngine

OpenXrossEngine is being prepared for a future public-source workflow.

## Design rules

- Keep Engine infrastructure provider-neutral.
- Put reusable contracts in `com.ztraqto.openxross.api` and implementation details outside it.
- Do not add Ztraqto Ads, Cloudflare-account, Stripe, or deployment-specific secrets to Engine code.
- Preserve compatibility for existing normal plugins unless a breaking change is explicitly versioned.
- Add tests for bootstrap/shard behavior when changing System Plugin or Shard APIs.
- Keep privileged System Plugin behavior deterministic and fail closed on malformed metadata/trust configuration.

## Before submitting a change

Run Java 17 builds and tests, build the example plugins, and confirm no generated files or credentials are included. Public contributions should follow the project license once one has been selected and added to the repository.
