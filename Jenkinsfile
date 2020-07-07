pipeline {
  agent {
    docker {
      image 'maven:alpine'
      args '-v /d/usr/maven:/root/.m2'
    }

  }
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B -Dmaven.test.skip=true clean install'
      }
    }

  }
}