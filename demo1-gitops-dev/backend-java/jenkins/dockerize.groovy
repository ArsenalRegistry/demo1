def label = "Partnership-${UUID.randomUUID().toString()}"

def isIpcRunningEnv = true
def isEpcRunningEnv = false

String getBranchName(branch) {
    branchTemp=sh returnStdout:true ,script:"""echo "$branch" |sed -E "s#origin/##g" """
    if(branchTemp){
        branchTemp=branchTemp.trim()
    }
    return branchTemp
}

podTemplate(cloud:'kubernetes',label: label, serviceAccount: 'default', namespace: 'demo1',
        containers: [
            containerTemplate(name: 'build-tools', image: 'ghcr.io/arsenalregistry/build-tools:v3.0', ttyEnabled: true, command: 'cat', privileged: true, alwaysPullImage: true),
            containerTemplate(name: 'jnlp', image: 'ghcr.io/arsenalregistry/inbound-agent:latest', args: '${computer.jnlpmac} ${computer.name}')
        ],
        volumes: [
                hostPathVolume(hostPath: '/etc/mycontainers', mountPath: '/var/lib/containers')
        ]
    ) {

    node(label){
        try {
            // freshStart
            def freshStart = params.freshStart

            if ( freshStart ) {
                container('build-tools'){
                    // remove previous working dir
                    print "freshStart... clean working directory ${env.JOB_NAME}"
                    sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
                    sleep 1000000
                }
            }

            def commitId

            
            def branchTemp
            //branch Name Parsing
            branchTemp = params.branchName
            branch=getBranchName(branchTemp)


            stage('Get Source') {
                git url: "https://gitlab.dspace.kt.co.kr/partnership/partnership-api.git",
                        credentialsId: 'partnership-git-credentials',
                        branch: "${branch}"
                commitId = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
            }
            def props = readProperties  file:'devops/jenkins/dockerize.properties'
            def tag = commitId
            def dockerRegistry = props['dockerRegistry']
            def image = props['image']
            def selector = props['selector']
            def namespace = props['namespace']
            def appname = props['appname']
            def apiKey = props['apiKey']
            def projectId = props['projectId']


            def unitTestEnable = true
            unitTestEnable = params.unitTestEnable

            // def mvnSettings = "${env.WORKSPACE}/devops/jenkins/settings.xml"
            // if ( isIpcRunningEnv ) {
            //     mvnSettings = "${env.WORKSPACE}/devops/jenkins/settings.xml"
            // } else {
            //     mvnSettings = "${env.WORKSPACE}/devops/jenkins/settings-epc.xml"
            // }

            /*
            stage("CodePrism RUN") {
                gl_CodePrismRunMD(apiKey, projectId)
            }
            */

            // def buildScope = params.buildScope

            stage('dspace nexus setting update') {
                container('build-tools') {
                    withCredentials([usernamePassword(credentialsId: 'partnership-nexus-credential', usernameVariable: 'nexusUsername', passwordVariable: 'nexusPassword')]) {
                        sh "export NEXUS_USERNAME=${nexusUsername}"
                        sh "export NEXUS_PASSWORD=${nexusPassword}"
                        sh "sed -i 's/\${env.NEXUS_USERNAME}/${nexusUsername}/g' gradle.properties"
                        sh "sed -i 's/\${env.NEXUS_PASSWORD}/${nexusPassword}/g' gradle.properties"
                    }
                }
            }



            stage('Gradle build &Unit Test') {
                container('build-tools') {
                    sh 'gradle build -Dgradle.wrapperUser=${NEXUS_ID} -Dgradle.wrapperPassword=${NEXUS_PASSWORD}' // 프로젝트 빌드
                    sh 'ls -alh'
                    sh 'ls ./build/libs -alh'

                    // sh "chmod 755 ./gradlew"
                    // sh "./gradlew build -Pipc"

                }
            }

             stage('Build Docker image') {
                 container('build-tools') {
                     withCredentials([usernamePassword(credentialsId:'c02-okd4-cz-tb-registry-credentials',usernameVariable:'USERNAME',passwordVariable:'PASSWORD')]) {
                         sh "podman login -u ${USERNAME} -p ${PASSWORD} ${dockerRegistry}  --tls-verify=false"
                         sh "podman build -t ${image}:${tag} --build-arg sourceFile=`find target -name '*.jar' | head -n 1` -f devops/jenkins/Dockerfile . --tls-verify=false"
                         sh "podman push ${image}:${tag} --tls-verify=false"
                         sh "podman tag ${image}:${tag} ${image}:latest"
                         sh "podman push ${image}:latest --tls-verify=false"
                     }
                 }
             }

            stage( 'Helm lint' ) {
                container('build-tools') {
                    dir('devops/helm/partnership-api'){
                        if ( isIpcRunningEnv ) {
                            sh """
                            # initial helm
                            # central helm repo can't connect
                            # setting stable repo by local repo
                            helm init --client-only --stable-repo-url "http://127.0.0.1:8879/charts" --skip-refresh
                            helm lint --namespace partnership --tiller-namespace partnership .
                            """
                        } else {
                            sh """
                            helm lint --namespace partnership .
                            """
                        }
                    }
                }
            }
        }
        catch(e) {
            container('build-tools'){
                print "Clean up ${env.JOB_NAME} workspace..."
                sh 'ls -A1|xargs rm -rf' /* clean up our workspace */
            }


            currentBuild.result = "FAILED"
            print " **Error :: " + e.toString()+"**"
        }
    }
}
