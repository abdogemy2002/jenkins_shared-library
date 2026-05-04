def call(Map config = [:]) {
    
    def gitUrl = config.gitUrl 
    def gitBranch = config.gitBranch ?: 'main'
    def serverPort = config.serverPort ?: '8081'
    def imageName = config.imageName ?: 'spring-app-a'
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
                    sh 'mvn -Dmaven.repo.local=/var/jenkins/.m2/repository clean compile'
                }
            }
            
            stage ('Test') {
                steps {
                    sh 'mvn -Dmaven.repo.local=/var/jenkins/.m2/repository test'
                }
                post {
                    always {
                        script {
                            echo "Ensuring all test containers are torn down..."
                            sh 'docker compose -f docker-compose.yml down -v || true'
                        }
                    }
                }
            }
            
            stage ('Package') {
                steps {
                    sh 'mvn -Dmaven.repo.local=/var/jenkins/.m2/repository package -DskipTests'
                }
            }
            
            stage ('Docker Build') {
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-ecr-uri', variable: 'SECRET_ECR_URI')
                    ]) {
                        withEnv(["IMAGE_NAME=${imageName}", "IMAGE_TAG=${imageTag}"]) {
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
                                
                                aws ecr get-login-password --region $SECRET_AWS_REGION | docker login --username AWS --password-stdin $SECRET_ECR_URI
                                
                                docker tag $VERSIONED_IMAGE $LATEST_IMAGE
                                
                                docker push $VERSIONED_IMAGE
                                docker push $LATEST_IMAGE
                            '''
                        }
                    }
                }
            }

            stage ('Deploy to Local EC2') {
                when {
                    branch 'main'
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-ecr-uri', variable: 'SECRET_ECR_URI')
                    ]) {
                        withEnv(["IMAGE_NAME=${imageName}", "IMAGE_TAG=${imageTag}", "SERVER_PORT=${serverPort}"]) {
                            sh '''
                                VERSIONED_IMAGE="$SECRET_ECR_URI/$IMAGE_NAME:$IMAGE_TAG"
                                echo "Deploying $VERSIONED_IMAGE locally on the Jenkins Worker..."
                                
                                docker stop $IMAGE_NAME || true
                                docker rm $IMAGE_NAME || true
                                
                                docker run -d \
                                    --name $IMAGE_NAME \
                                    -p $SERVER_PORT:$SERVER_PORT \
                                    --restart unless-stopped \
                                    $VERSIONED_IMAGE
                                    
                                echo "Deployment successful! Container is running on port $SERVER_PORT."
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
