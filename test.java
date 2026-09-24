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


pipeline {
    agent any

    stages {
        stage('Check Available Selenium Images') {
            steps {
                sh '''
                    echo "===== Checking selenium/node-chrome ====="
                    docker pull target/f1-tools-docker-images/selenium/node-chrome:latest || echo "NOT FOUND: selenium/node-chrome"

                    echo "===== Checking selenium/hub ====="
                    docker pull target/f1-tools-docker-images/selenium/hub:latest || echo "NOT FOUND: selenium/hub"

                    echo "===== Checking selenium/standalone-chrome (nested path) ====="
                    docker pull target/f1-tools-docker-images/selenium/standalone-chrome:latest || echo "NOT FOUND: selenium/standalone-chrome"

                    echo "===== Listing all local images pulled so far ====="
                    docker images | grep -i selenium
                '''
            }
        }
    }
}

docker run --rm target/f1-tools-docker-images/selenium/node-chrome-debug:latest google-chrome --version
