# Enterprise CI/CD Pipeline: Spring Petclinic Microservice

## 1. Architecture Overview
This project implements a fully automated, declarative Continuous Integration (CI) pipeline for a Spring Boot application. The pipeline leverages Jenkins Shared Libraries to standardize build executions across multiple branches, resulting in a production-ready Docker image pushed securely to AWS Elastic Container Registry (ECR).

**Flow overview:**

![CI/CD Pipeline Architecture](/project%20overview%20.png)

## 2. Infrastructure & Prerequisites
To run this pipeline, the following infrastructure components must be active:

*   **Jenkins Controller:** Configured with the Multibranch Pipeline plugin and GitHub integration.
*   **Jenkins Worker Node (EC2):**
    *   **OS:** Ubuntu Linux
    *   **OS-Level Tools Installed:** Docker, Docker Compose Plugin (`v2.x`), AWS CLI v2.
    *   **Networking:** Outbound internet access; Inbound Security Group rule for TCP 8081 (if testing the app directly).
*   **Pipeline-Managed Tools:**
    *   **Java & Build:** JDK and Maven are dynamically provisioned at runtime by the Jenkins controller via the `tools {}` block .
*   **AWS Infrastructure:**
    *   **IAM Role:** An Instance Profile named `Jenkins-Worker-ECR-Role` with `AmazonEC2ContainerRegistryPowerUser` attached to the worker node.
    *   **ECR Repository:** A private repository named `jenkins-image-repo/service-a`.

## 3. GitHub Configuration & Branch Protection
The repository enforces strict quality control before any code reaches production.

*   **Webhooks:** Configured to push events to the Jenkins controller. 
*   **Branch Protection (`main`):**
    *   Direct pushes are disabled. Pull Requests are required.
    *   **Status Checks:** The Jenkins CI pipeline must return a "Success" status before the "Merge" button is unlocked.

## 4. Jenkins Shared Library
All pipeline logic is abstracted into a Jenkins Shared Library to maintain DRY (Don't Repeat Yourself) principles.

*   **Repository:** `jenkins_shared-library`
*   **Command:** `standardPipeline.groovy`
*   **Usage in Application `Jenkinsfile`:**
    ```groovy
    @Library('java-pipeline-template') _
	javaPipelineTemplate(
		gitUrl: 'https://github.com/abdogemy2002/spring-app-a.git',
		gitBranch: 'main',
		serverPort: '8081',
		imageName: 'service-a',
		imageTag: "v1.0.${env.BUILD_NUMBER}"
	)
    ```

## 5. Pipeline Stages
The pipeline executes the following stages sequentially. If any stage fails, the pipeline immediately aborts (Fail-Fast).

1.  **Clone Code:** Pulls the specific branch triggering the build.
2.  **Config:** Injects `server.port=8081` into the `application.properties`.
3.  **Clean & Compile:** Executes `mvn clean compile` using a localized Maven cache (`/var/jenkins/.m2/repository`) for speed optimization.
4.  **Test:** Executes `mvn test`. Spring Boot uses Docker Compose to spin up an ephemeral PostgreSQL container for integration testing. A `post` block ensures `docker compose down -v` runs to prevent orphaned containers.
5.  **Package:** Compiles the application into a `.jar` artifact (`mvn package -DskipTests`).
6.  **Docker Build:** Packages the `.jar` into a lightweight `eclipse-temurin:17-jre-alpine` image. Runs as a non-root `spring` user for security.
7.  **Docker Push to ECR:** (Executes on `main` branch only). Authenticates natively via the attached IAM Role and pushes both the versioned tag and the `latest` tag to AWS ECR.
#### dev branch build 
![DEV branch build](/image.png)
## 6. Security Implementations
*   **Least Privilege:** The Docker container drops root privileges and executes the application as a dedicated system user.
*   **Secret Management:** AWS credentials are not stored in Jenkins. Authentication is handled dynamically via the EC2 metadata service and IAM roles.
*   **Groovy Interpolation:** Shell commands interacting with AWS use single quotes (`'''`) to prevent plain-text secrets from being exposed in Jenkins logs.



## 7. Troubleshooting & Known Issues

| Symptom | Root Cause | Resolution |
| :--- | :--- | :--- |
| `NoSuchMethodError 'standardPipeline'` | Jenkins cannot find the file in the shared library. | Ensure the file in `vars/` is named exactly `standardPipeline.groovy` (case-sensitive). |
| `Bind for 0.0.0.0:5432 failed` | A previous pipeline aborted, leaving an orphaned Postgres container hogging the port. | SSH to worker node, run `docker ps`, and `docker rm -f [CONTAINER_ID]`. Ensure the pipeline's `post` cleanup block is intact. |
| `aws: not found` during ECR push | AWS CLI is missing on the worker node. | Install AWS CLI v2 via the official `.zip` installer (do not use `apt`). |
| `Repository does not exist in the registry` | Pushing to an uncreated ECR repo. | Manually create the private repository in the AWS Console before pushing. |




