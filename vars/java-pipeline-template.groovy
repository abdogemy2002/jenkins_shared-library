pipeline {
    agent{
       label 'jenkins-worker' 
    } 
    tools {
        maven 'MVN-3.9.15'
        jdk 'JDK-17.0.13'
    }
    stages {
        stage ('clone code'){
            steps {
              git branch: 'main',
            url: 'https://github.com/spring-projects/spring-petclinic.git'  
            }
        }
        stage ('config'){
            steps{
                sh 'echo "server.port=9090" >>src/main/resources/application.properties'
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
                sh 'nohup java -jar target/*.jar &'
                sh 'sleep 300'
            }
        }
    }
}