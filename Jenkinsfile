pipeline {
    agent {
        kubernetes {
        //cloud 'kubernetes'
        label 'maven'
        yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: maven
    image: maven:3.6.3-jdk-8-slim
    command: ['cat']
    tty: true
    volumeMounts:
    - name: maven-repo
      mountPath: /root/.m2/repository
  volumes:
  - name: maven-repo
    hostPath:
      path: /root/.m2/repository
"""
        }
    }

    stages {
        stage ('build') {
            steps {
                container ('maven') {
                    sh 'mvn -B -Dmaven.test.skip=true -Dgpg.skip=true clean install'
                }
            }
        }
    }
}
