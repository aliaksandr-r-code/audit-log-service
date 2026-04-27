## Project map

Audit functionality events inside of company
Example: Messages in teams
Search, CRUD operations

## Technology Stack: 
  - Java 21, 
  - Spring Boot 3.x, 
  - Postgres, 
  - Kafka
  - Gradle
  - JPA (hibernate provider)
  - Spring Data JPA


## Invariants

- Immutable Events
- Code style with spottles
- Make Sonar checks
- Application is a Kafka Consumer
- Application is not a Kafka Producer
## Architectural rules

- Hexagonal architecture
- Use Spring boot for port and adapters
- Api could call business logic layer
- Business logic is forbidden to call api layer 
- Use CQRS
- Integration test coverage 
- Simplicity first
- Persistence layer works with entity objects  

## Implementation rules

- Spring Data repositories use only interfaces
- Integration test should use TestContainers
- Integration test should not use in-memory database
- In case when input and output data for tests contains more than two fields such test data should be stored in JSON resource files instead of test source code
- Database in tests is Postgres
- If some unclear leave as to do

## Expected results

- Source Code
- Docker compose script
- Github action pipeline for build 

## Verification
- ./graddlew build
- ./graddlew test

## Definition of done
- gradlew build is successful
- Integration tests are successful 

