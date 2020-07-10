pipeline {
  agent any
  tools {
    maven 'Default'
  }
  stages {
    stage('Build') {
    when { isUnix() }
    steps {
      sh 'mvn -B -Dmaven.test.skip=true clean install'
    }}
    stage('WinBuild') {
    when { not isUnix() }
    steps {
      bat 'mvn -B -Dmaven.test.skip=true clean install'
    }}
  }
}
