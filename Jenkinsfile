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
                withCredentials([usernamePassword(usernameVariable: 'SU_USR', passwordVariable: 'SU_PWD', credentialsId: "$SHADOW_USER_CREDENTIAL_ID")]) {
                    sh 'echo "$SU_USR : $SU_PWD"'
                }
                container ('maven') {
                    sh 'mvn -B -Dmaven.test.skip=true -Dgpg.skip=true clean install -pl rxlib-x -am'
                }
            }
        }
    }
}
