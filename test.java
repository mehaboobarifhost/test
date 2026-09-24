pipeline {
    agent any

    stages {
        stage('Check Selenium Chrome Image Version') {
            steps {
                sh '''
                    echo "Pulling current image..."
                    docker pull target/f1-tools-docker-images/selenium-standalone-chrome:latest

                    echo "Checking Chrome version inside the image..."
                    docker run --rm target/f1-tools-docker-images/selenium-standalone-chrome:latest google-chrome --version

                    echo "Checking available tags (if registry supports listing)..."
                    docker images target/f1-tools-docker-images/selenium-standalone-chrome
                '''
            }
        }
    }
}
   curl -s -u <artifactory_user>:<artifactory_pass> "https://target/artifactory/api/docker/f1-tools-docker-images/v2/selenium-standalone-chrome/tags/list"
