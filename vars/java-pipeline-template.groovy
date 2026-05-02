// vars/standardPipeline.groovy
def call(Map config = [:]) {
    
    // Define parameters with fallback default values
    def gitUrl = config.gitUrl ?: 'https://github.com/spring-projects/spring-petclinic-a.git'
    def gitBranch = config.gitBranch ?: 'main'
    def serverPort = config.serverPort ?: '9090'

    pipeline {
        agent {
           // Explicitly targeting your new worker node
           label 'jenkins-worker' 
        } 
        
        tools {
            // Your exact configured tool versions
            maven 'MVN-3.9.15'
            jdk 'JDK-17.0.13'
        }
        
        stages {
            stage ('clone code'){
                steps {
                    echo "Cloning repository: ${gitUrl} (Branch: ${gitBranch})"
                    git branch: "${gitBranch}",
                        url: "${gitUrl}"  
                }
            }
            
            stage ('config'){
                steps{
                    echo "Configuring application to run on port: ${serverPort}"
                    sh "echo \"server.port=${serverPort}\" >> src/main/resources/application.properties"
                }
            }
            
            stage ('clean compile'){
                steps {
                    sh 'mvn clean compile'
                }
            }
            
            stage ('test'){
                steps {
                    sh 'mvn test'
                }
            }
            
            stage ('package'){
                steps {
                    sh 'mvn package'
                }
            }
            
            stage ('run'){
                steps {
                    echo "Starting Spring Boot application on port ${serverPort}..."
                    sh 'nohup java -jar target/*.jar &'
                    
                    // Temporary sleep to keep the pipeline alive while you verify the app
                    sh 'sleep 300'
                }
            }
        }
    }
}