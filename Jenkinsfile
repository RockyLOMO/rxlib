pipeline {
    agent {
        node {
            label 'maven'
        }
    }

    environment {
        SHADOW_USER_CREDENTIAL_ID = 'shadow-user'
    }

    stages {
        stage("Env Variables") {
            steps {
                sh "printenv"
            }
        }

        stage ('build') {
            when {
                branch 'master'
            }
            steps {
                sh 'echo ${SHADOW_USER_PASSWORD}'
                container ('maven') {
                    sh 'mvn -B -Dmaven.test.skip=true -Dgpg.skip=true clean install'
                }
            }
        }
    }
}
