Run tests for the service named "$ARGUMENTS".

## Service → test command mapping

Determine the service type from the name:

**Java services** (discovery-service, connection-service, rating-service, report-service, admin-service):
```
cd services/<service-name>
./mvnw test
```

**.NET services** (user-service, chat-service, notification-service, api-gateway):
```
cd services/<service-name>
dotnet test --no-build
```

**Angular frontend**:
```
cd frontend
npm run test:unit
```

**If "$ARGUMENTS" is "all"**: run tests for every service that has an implementation directory, sequentially. Report pass/fail per service at the end.

## Output format
- Show the test runner's output (truncated to failures + summary if verbose)
- Final line per service: `service-name: X passed, Y failed`
- If any test fails, show the full failure message and the file:line reference
- If a service directory doesn't exist yet, note "not yet implemented — skip"
