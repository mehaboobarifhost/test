pipeline {

    agent {
        ecs {
            inheritFrom 'svn-core'
            image ''
        }
    }

    parameters {
        string(name: 'SOURCE_PROJECT', defaultValue: 'zzz', description: 'Old space key (tags like @zzz-123 and @TEST_zzz-123 are stripped)')
        string(name: 'TARGET_PROJECT', defaultValue: 'yyy', description: 'New space key where Tests are created')
        booleanParam(name: 'STRIP_REQ_TAGS', defaultValue: false, description: 'Also strip @REQ_zzz-nnn requirement-link tags (leave off if those stories still exist in zzz and you want the links)')
        booleanParam(name: 'DRY_RUN', defaultValue: true, description: 'Validate and clean the zip only. Do NOT create anything in Jira/Xray')
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
    }

    environment {
        // Same proxy as your test pipeline - adjust if this job runs on a different agent
        http_proxy  = "http://proxy-euie.aws.XYZ.net:3128"
        https_proxy = "http://proxy-euie.aws.XYZ.net:3128"
        HTTP_PROXY  = "http://proxy-euie.aws.XYZ.net:3128"
        HTTPS_PROXY = "http://proxy-euie.aws.XYZ.net:3128"
        NO_PROXY    = ".XYZ.net"
        no_proxy    = ".XYZ.net"
        XRAY_EU     = "https://eu.xray.cloud.getxray.app"
    }

    stages {

        stage('Validate parameters') {
            steps {
                script {
                    if (!params.TARGET_PROJECT?.trim() || !params.SOURCE_PROJECT?.trim()) {
                        error 'SOURCE_PROJECT and TARGET_PROJECT are required'
                    }
                    if (params.TARGET_PROJECT.trim() == params.SOURCE_PROJECT.trim()) {
                        error 'TARGET_PROJECT must differ from SOURCE_PROJECT'
                    }
                    echo "Source ${params.SOURCE_PROJECT}-*  ->  Target ${params.TARGET_PROJECT}-*   (DRY_RUN=${params.DRY_RUN})"
                }
            }
        }

        stage('Upload features zip') {
            steps {
                script {
                    // Pauses the build and shows an upload dialog. The file is stashed under the
                    // parameter name, so unstash recreates it as features.zip in the workspace.
                    input(message: 'Upload the zipped .feature files', parameters: [file(name: 'features.zip')])
                }
                unstash 'features.zip'
            }
        }

        stage('Validate and clean features') {
            steps {
                sh '''
                    set -e
                    rm -rf work && mkdir -p work/in
                    unzip -tq features.zip
                    unzip -q features.zip -d work/in

                    COUNT=$(find work/in -name '*.feature' | wc -l)
                    echo "Feature files found: $COUNT"
                    [ "$COUNT" -gt 0 ] || { echo "ERROR: no .feature files in the zip"; exit 1; }

                    echo "Old-key tags before cleaning:"
                    grep -rEoh "@((TEST|REQ)_)?${SOURCE_PROJECT}-[0-9]+" work/in --include=*.feature | sort | uniq -c | sort -rn | head -20 || true

                    # Strip old test tags so Xray creates NEW tests in the target space
                    find work/in -name '*.feature' -exec sed -i -E "s/@(TEST_)?${SOURCE_PROJECT}-[0-9]+//g" {} +
                    if [ "${STRIP_REQ_TAGS}" = "true" ]; then
                        find work/in -name '*.feature' -exec sed -i -E "s/@REQ_${SOURCE_PROJECT}-[0-9]+//g" {} +
                    fi

                    LEFT=$(grep -rEc "@(TEST_)?${SOURCE_PROJECT}-[0-9]+" work/in --include=*.feature | awk -F: '{s+=$2} END{print s+0}')
                    [ "$LEFT" -eq 0 ] || { echo "ERROR: $LEFT old ${SOURCE_PROJECT}- tags remain"; exit 1; }

                    ( cd work/in && zip -qr ../clean_features.zip . )
                    echo "Cleaned zip ready: work/clean_features.zip"
                '''
                archiveArtifacts artifacts: 'work/clean_features.zip', allowEmptyArchive: false
            }
        }

        stage('Import to Xray') {
            when { expression { return !params.DRY_RUN } }
            steps {
                withCredentials([
                    string(credentialsId: 'sampleclientid',     variable: 'jira_tv_client_id'),
                    string(credentialsId: 'samplesecret', variable: 'jira_tv_client_secret')
                ]) {
                    sh '''
                        set +x
                        TOKEN=$(curl -sS -X POST -H "Content-Type: application/json" \
                          "$XRAY_EU/api/v2/authenticate" \
                          --data "{\\"client_id\\":\\"$jira_tv_client_id\\",\\"client_secret\\":\\"$jira_tv_client_secret\\"}" | tr -d '"')
                        [ -n "$TOKEN" ] || { echo "ERROR: Xray authentication failed"; exit 1; }

                        echo "Importing into project ${TARGET_PROJECT} ..."
                        curl -sS -H "Authorization: Bearer $TOKEN" \
                          -F "file=@work/clean_features.zip" \
                          "$XRAY_EU/api/v2/import/feature?projectKey=${TARGET_PROJECT}" \
                          -o import_result.json

                        echo "Xray response (first 2000 chars):"
                        head -c 2000 import_result.json; echo

                        ERRS=$(jq '(.errors // []) | length' import_result.json)
                        CREATED=$(jq '(.updatedOrCreatedTests // []) | length' import_result.json)
                        echo "Created/updated tests: $CREATED   Errors: $ERRS"
                        [ "$ERRS" -eq 0 ] || { echo "ERROR: Xray reported import errors (see import_result.json)"; exit 1; }
                        [ "$CREATED" -gt 0 ] || { echo "ERROR: nothing was created"; exit 1; }
                    '''
                }
            }
            post {
                always { archiveArtifacts artifacts: 'import_result.json', allowEmptyArchive: true }
            }
        }

        stage('Export new yyy tests with tags') {
            when { expression { return !params.DRY_RUN } }
            steps {
                withCredentials([
                    string(credentialsId: 'sampleclientid',     variable: 'jira_tv_client_id'),
                    string(credentialsId: 'samplesecret', variable: 'jira_tv_client_secret')
                ]) {
                    // Non-fatal: the import already succeeded. This only produces tagged features for your repo.
                    catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                        sh '''
                            set +x
                            TOKEN=$(curl -sS -X POST -H "Content-Type: application/json" \
                              "$XRAY_EU/api/v2/authenticate" \
                              --data "{\\"client_id\\":\\"$jira_tv_client_id\\",\\"client_secret\\":\\"$jira_tv_client_secret\\"}" | tr -d '"')

                            jq -r '.updatedOrCreatedTests[].key' import_result.json > new_keys.txt
                            echo "New keys:"; cat new_keys.txt

                            KEYS=$(paste -sd';' new_keys.txt)
                            curl -sS -H "Authorization: Bearer $TOKEN" \
                              "$XRAY_EU/api/v2/export/cucumber?keys=${KEYS}" \
                              -o tagged_features.zip
                            unzip -tq tagged_features.zip
                        '''
                    }
                }
                archiveArtifacts artifacts: 'new_keys.txt, tagged_features.zip', allowEmptyArchive: true
            }
        }
    }

    post {
        success { echo params.DRY_RUN ? 'Dry run OK - nothing was created. Re-run with DRY_RUN=false.' : 'Import finished. Commit tagged_features.zip contents to the repo.' }
        failure { echo 'Import job failed - check the stage logs and import_result.json' }
    }
}
