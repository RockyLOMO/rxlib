pipeline {
  agent any
  tools {
    maven 'Default'
  }
  stages {
    stage('Build') {
      steps {
        bat 'mvn -B -Dmaven.test.skip=true clean install'
      }
    }
  }
}
