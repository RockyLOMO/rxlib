pipeline {
  agent any
  tools {
    maven 'Default'
  }
  stages {
    stage('Build') {
      steps {
        bat 'mvn -B -Dmaven.test.skip=true clean install --settings D:\\cache\\apache-maven-3.6.3\\conf\\settings.xml'
      }
    }
  }
}
