def call(Map config = [:]) {
    
    // --- Configuration Variables ---
    def gitUrl = config.gitUrl 
    def gitBranch = config.gitBranch ?: 'main'
    def serverPort = config.serverPort ?: '9090'
    def imageName = config.imageName ?: 'spring-petclinic'
    def imageTag = config.imageTag ?: 'latest'

    pipeline {
        agent {
           label 'jenkins-worker' 
        } 
        
        tools {
            maven 'MVN-3.9.15'
            jdk 'JDK-17.0.13'
        }
        
        stages {
            stage ('Clone Code') {
                steps {
                    echo "Cloning repository: ${gitUrl} (Branch: ${gitBranch})"
                    git branch: "${gitBranch}", url: "${gitUrl}"  
                }
            }
            
            stage ('Config') {
                steps {
                    echo "Injecting server port: ${serverPort}"
                    sh "echo \"server.port=${serverPort}\" >> src/main/resources/application.properties"
                }
            }
            
            stage ('Clean & Compile') {
                steps {
                    // Optimized with local Maven dependency caching
                    sh 'mvn -Dmaven.repo.local=/var/jenkins/.m2/repository clean compile'
                }
            }
            
            stage ('Test') {
                steps {
                    // Optimized with local Maven dependency caching
                    sh 'mvn -Dmaven.repo.local=/var/jenkins/.m2/repository test'
                }
                post {
                    always {
                        script {
                            echo "Ensuring all test containers are torn down..."
                            // Forcefully stop and remove database containers to prevent port collisions on subsequent builds
                            sh 'docker compose -f docker-compose.yml down -v || true'
                        }
                    }
                }
            }
            
            stage ('Package') {
                steps {
                    // Optimized with local Maven dependency caching
                    sh 'mvn -Dmaven.repo.local=/var/jenkins/.m2/repository package -DskipTests'
                }
            }
            
            stage ('Docker Build') {
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-ecr-uri', variable: 'SECRET_ECR_URI')
                    ]) {
                        // Use withEnv to pass our Groovy variables to the Linux shell safely
                        withEnv(["IMAGE_NAME=${imageName}", "IMAGE_TAG=${imageTag}"]) {
                            // Notice the use of single quotes ''' here. Groovy ignores the variables, preventing interpolation warnings.
                            sh '''
                                VERSIONED_IMAGE="$SECRET_ECR_URI/$IMAGE_NAME:$IMAGE_TAG"
                                echo "Building Docker Image: $VERSIONED_IMAGE"
                                
                                docker build -t $VERSIONED_IMAGE .
                            '''
                        }
                    }
                }
            }
            
            stage ('Docker Push to ECR') {
                when {
                    branch 'main'
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-ecr-uri', variable: 'SECRET_ECR_URI'),
                        string(credentialsId: 'aws-ecr-region', variable: 'SECRET_AWS_REGION')
                    ]) {
                        withEnv(["IMAGE_NAME=${imageName}", "IMAGE_TAG=${imageTag}"]) {
                            sh '''
                                VERSIONED_IMAGE="$SECRET_ECR_URI/$IMAGE_NAME:$IMAGE_TAG"
                                LATEST_IMAGE="$SECRET_ECR_URI/$IMAGE_NAME:latest"
                                
                                echo "Main branch detected. Pushing to AWS ECR..."
                                
                                # 1. Authenticate with AWS ECR (Bash resolves the secrets securely)
                                aws ecr get-login-password --region $SECRET_AWS_REGION | docker login --username AWS --password-stdin $SECRET_ECR_URI
                                
                                # 2. Apply the 'latest' floating tag locally
                                docker tag $VERSIONED_IMAGE $LATEST_IMAGE
                                
                                # 3. Push both tags
                                docker push $VERSIONED_IMAGE
                                docker push $LATEST_IMAGE
                            '''
                        }
                    }
                }
            }
        }
        
        post {
            always {
                cleanWs()
            }
        }
    }
}