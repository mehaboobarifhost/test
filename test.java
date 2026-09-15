pipeline {
    agent any

    environment {
        XRAY_CLIENT_ID = credentials('xray-client-id')
        XRAY_CLIENT_SECRET = credentials('xray-client-secret')
    }

    stages {
        stage('Test Xray Token') {
            steps {
                script {
                    def response = sh(
                        script: '''
                            curl -s -w "\\nHTTP_STATUS:%{http_code}" -X POST \
                            "https://xray.cloud.getxray.app/api/v2/authenticate" \
                            -H "Content-Type: application/json" \
                            -d "{\\"client_id\\": \\"$XRAY_CLIENT_ID\\", \\"client_secret\\": \\"$XRAY_CLIENT_SECRET\\"}"
                        ''',
                        returnStdout: true
                    ).trim()

                    echo "Raw response: ${response}"

                    def status = (response =~ /HTTP_STATUS:(\d+)/)[0][1]
                    def body = response.replaceAll(/\nHTTP_STATUS:\d+/, '')

                    echo "HTTP Status: ${status}"

                    if (status == "200") {
                        echo "✅ Token generated successfully."
                        def token = body.replaceAll('"', '')

                        // Test an authenticated call
                        def testResponse = sh(
                            script: """
                                curl -s -w "\\nHTTP_STATUS:%{http_code}" -X POST \
                                "https://xray.cloud.getxray.app/api/v2/graphql" \
                                -H "Authorization: Bearer ${token}" \
                                -H "Content-Type: application/json" \
                                -d '{"query": "query { getTests(limit: 1) { total } }"}'
                            """,
                            returnStdout: true
                        ).trim()

                        echo "Test call response: ${testResponse}"
                    } else {
                        echo "❌ Token generation failed."
                        echo "Body: ${body}"
                        error("Xray authentication failed with status ${status}")
                    }
                }
            }
        }
    }
}
