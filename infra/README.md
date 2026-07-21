# Parkable SAM deployment

This directory contains a minimal SAM template for the Phase 2 API.

## Packaging note

No extra build artifact is required for the Lambda package. The existing shaded jar at `backend/target/parkable-cli.jar` already contains the Lambda handlers and their dependencies, so the template points `CodeUri` at that path directly.

## Deploy

1. Build the backend jar:
   ```powershell
   mvn -f backend/pom.xml package
   ```
2. Deploy with SAM:
   ```powershell
   sam deploy --template-file infra/template.yaml --config-file infra/samconfig.toml.example --guided
   ```

## Local API smoke test

If SAM CLI and Docker are available locally, you can run:

```powershell
sam local start-api --template-file infra/template.yaml
```

The API endpoints are:

- `POST /scan`
- `GET /check`
- `GET /nearby`
