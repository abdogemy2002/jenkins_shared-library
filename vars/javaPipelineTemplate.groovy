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
                    sh 'mvn clean compile'
                }
            }
            
            stage ('Test') {
                steps {
                    sh 'mvn test'
                }
            }
            
            stage ('Package') {
                steps {
                    sh 'mvn package -DskipTests'
                }
            }
            
            stage ('Docker Build') {
                steps {
                    withCredentials([
                        string(credentialsId: 'aws-ecr-uri', variable: 'SECRET_ECR_URI')
                    ]) {
                        script {
                            def versionedImage = "${SECRET_ECR_URI}/${imageName}:${imageTag}"
                            echo "Building Docker Image: ${versionedImage}"
                            
                            // Always build to validate the Dockerfile compiles successfully
                            sh "docker build -t ${versionedImage} ."
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
                        script {
                            def versionedImage = "${SECRET_ECR_URI}/${imageName}:${imageTag}"
                            def latestImage = "${SECRET_ECR_URI}/${imageName}:latest"
                            
                            echo "Main branch detected. Pushing to AWS ECR..."
                            
                            // 1. Authenticate with AWS ECR
                            sh "aws ecr get-login-password --region ${SECRET_AWS_REGION} | docker login --username AWS --password-stdin ${SECRET_ECR_URI}"
                            
                            // 2. Apply the 'latest' floating tag locally
                            sh "docker tag ${versionedImage} ${latestImage}"
                            
                            // 3. Push both the immutable version tag and the latest tag
                            sh "docker push ${versionedImage}"
                            sh "docker push ${latestImage}"
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