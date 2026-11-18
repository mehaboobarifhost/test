pipeline {

    agent {
        ecs {

        }
    }

    parameters {
        string(name: 'SOURCE_PROJECT', defaultValue: 'yyy', description: 'Old space key (tags like @yyy-123 and @TEST_yyy-123 are stripped)')
        string(name: 'TARGET_PROJECT', defaultValue: 'PCI', description: 'New space key where Tests are created')
        string(name: 'FEATURES_DIR', defaultValue: 'src/test/resources/features', description: 'Folder in the repo that holds the .feature files (adjust to your framework)')
        string(name: 'FEATURE_FILTER', defaultValue: '', description: 'Only import files whose path contains this text, e.g. UpdateContent. Leave BLANK to import ALL files')
        booleanParam(name: 'STRIP_REQ_TAGS', defaultValue: false, description: 'Also strip @REQ_yyy-nnn requirement-link tags (leave off if those stories still exist in yyy and you want the links)')
        booleanParam(name: 'DRY_RUN', defaultValue: true, description: 'Collect and clean only. Do NOT create anything in Jira/Xray')
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
    }

    environment {
        // Same proxy as your test pipeline - adjust if this job runs on a different agent
        http_proxy  = "http://proxy-euie.aws.xyz.net:3128"
        https_proxy = "http://proxy-euie.aws.xyz.net:3128"
        HTTP_PROXY  = "http://proxy-euie.aws.xyz.net:3128"
        HTTPS_PROXY = "http://proxy-euie.aws.xyz.net:3128"
        NO_PROXY    = ".xyz.net"
        no_proxy    = ".xyz.net"
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
                    echo "Features dir: ${params.FEATURES_DIR}   Filter: '${params.FEATURE_FILTER}' (blank = all)"
                }
            }
        }

        stage('Checkout and collect features') {
            steps {
                checkout scm   // needs "Pipeline script from SCM"; fails if the script was pasted into the job

                sh '''
                    set -e
                    [ -d "$FEATURES_DIR" ] || { echo "ERROR: folder $FEATURES_DIR not found in the repo"; exit 1; }
                    rm -rf work && mkdir -p work/in

                    # List all .feature files, with paths relative to FEATURES_DIR
                    ( cd "$FEATURES_DIR" && find . -type f -name '*.feature' | sort ) > work/all_files.txt
                    if [ -n "$FEATURE_FILTER" ]; then
                        grep -F -- "$FEATURE_FILTER" work/all_files.txt > work/selected.txt || true
                    else
                        cp work/all_files.txt work/selected.txt
                    fi

                    COUNT=$(wc -l < work/selected.txt)
                    echo "Feature files selected: $COUNT"
                    cat work/selected.txt
                    [ "$COUNT" -gt 0 ] || { echo "ERROR: no .feature files matched the filter"; exit 1; }

                    # Copy ONLY the selected files into work/in, keeping folder structure
                    while IFS= read -r f; do
                        mkdir -p "work/in/$(dirname "$f")"
                        cp "$FEATURES_DIR/$f" "work/in/$f"
                    done < work/selected.txt
                '''
            }
        }

        stage('Clean tags') {
            steps {
                sh '''
                    set -e
                    echo "Old-key tags before cleaning:"
                    grep -rEoh "@((TEST|REQ)_)?${SOURCE_PROJECT}-[0-9]+" work/in --include=*.feature | sort | uniq -c | sort -rn | head -20 || true

                    # Strip old test tags (in the copies only) so Xray creates NEW tests in the target space
                    find work/in -name '*.feature' -exec sed -i -E "s/@(TEST_)?${SOURCE_PROJECT}-[0-9]+//g" {} +
                    if [ "${STRIP_REQ_TAGS}" = "true" ]; then
                        find work/in -name '*.feature' -exec sed -i -E "s/@REQ_${SOURCE_PROJECT}-[0-9]+//g" {} +
                    fi

                    LEFT=$(grep -rEc "@(TEST_)?${SOURCE_PROJECT}-[0-9]+" work/in --include=*.feature | awk -F: '{s+=$2} END{print s+0}')
                    [ "$LEFT" -eq 0 ] || { echo "ERROR: $LEFT old ${SOURCE_PROJECT}- tags remain"; exit 1; }

                    ( cd work/in && zip -qr ../clean_features.zip . )
                    unzip -tq work/clean_features.zip
                    echo "Cleaned zip ready: work/clean_features.zip"
                '''
                archiveArtifacts artifacts: 'work/clean_features.zip, work/selected.txt', allowEmptyArchive: false
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

        stage('Export new PCI tests with tags') {
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
