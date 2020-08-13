pipeline {
  agent any
  tools {
    maven 'Default'
  }
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B -Dmaven.test.skip=true install'
      }
    }
  }
}
