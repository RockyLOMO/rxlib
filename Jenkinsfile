pipeline {
  agent any
  tools {
    maven 'Default'
  }
  stages {
    stage('Build') {
      if (isUnix()) {
        sh 'mvn -B -Dmaven.test.skip=true clean install'
      } else {
        bat 'mvn -B -Dmaven.test.skip=true clean install'
      }
    }
  }
}
