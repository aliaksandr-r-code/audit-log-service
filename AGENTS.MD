## Project map

Audit functionality events inside of company
Example: Messages in teams
Search, CRUD operations

Functionality of REST API:
  - create event
  - search event by parameters
  - archiving events 

Domain model
  Event:
   - actor  
   - action
   - timestamp - calculated automatically when event is registered
   
## Technology Stack: 
  - Java 21, 
  - Spring Boot 3.x, 
  - Postgres, 
  - Gradle
  - JPA (hibernate provider)
  - Spring Data JPA


## Invariants

- Immutable Events

## Architectural rules

- Hexagonal architecture
- Use Spring boot for adapters
- Api could call business logic layer
- Business logic is forbidden to call api layer 
- Use CQRS
- Integration test coverage 
- Simplicity first
- Persistence layer works with entity objects
- Observability and healh checks

## Implementation rules

- Integration tests should use TestContainers
- Integration tests should not use in-memory database
- In case when input and output data for tests contains more than two fields such test data should be stored in JSON resource files instead of test source code
- Database in tests is Postgres
- Implement Repository in a declarative style and only as interface extending Spring Data JpaRepository
- Repository should be annotated with Spring Repository annotation  
- Any business Logic should be implemented as a services  
- Transaction should be applicable only in a service layer 
- Search should be implemented using Specifications
- reasonable logging with slf4j
- generate README.MD
- If some unclear leave as to do 

## Code style
- Code style with spottles
- Make Sonar checks


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

