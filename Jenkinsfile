pipeline {
  agent any
  tools {
    maven 'Default'
  }
  stages {
    stage('Build') {
      if (isUnix()) {
        steps {
          sh 'mvn -B -Dmaven.test.skip=true clean install'
        }
      } else {
        steps {
          bat 'mvn -B -Dmaven.test.skip=true clean install'
        }
      }
    }
  }
}
