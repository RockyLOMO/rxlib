pipeline {
    agent {
        node {
            label 'maven'
        }
    }

    environment {
        SHADOW_USER_CREDENTIAL_ID = 'shadow-user'
        PORT = '9900'
        PARAMS = '-shadowMode=1 -port=${PORT} -connectTimeout=40000 "-shadowUser=${SHADOW_USER_PASSWORD}"'
    }

    stages {
        stage ('build') {
            steps {
                echo "${SHADOW_USER_PASSWORD}"
                echo "env: ${PARAMS}"
                container ('maven') {
                    sh 'mvn -B -Dmaven.test.skip=true -Dgpg.skip=true clean install'
                }
            }
        }
    }
}
