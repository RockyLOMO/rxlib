pipeline {
    agent {
        node {
            label 'maven'
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
