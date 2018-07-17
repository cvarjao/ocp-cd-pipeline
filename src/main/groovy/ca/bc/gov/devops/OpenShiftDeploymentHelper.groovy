
package ca.bc.gov.devops

class OpenShiftDeploymentHelper extends OpenShiftHelper{
    def config

    //[object:null, phase:'New', buildName:null, builds:0, dependsOn:[], output:[from:[kind:''], to:['kind':'']] ]
    //Map cache = [:] //Indexed by key
    
    public OpenShiftDeploymentHelper(config){
        this.config=config
    }

    private List loadDeploymentTemplates(){
        Map parameters =[
                'NAME_SUFFIX':config.app.deployment.suffix,
                'ENV_NAME': config.app.deployment.name,
                'BUILD_ENV_NAME': config.app.build.name
        ]
        return loadTemplates(config, config.app.deployment, parameters)
    }


    private void applyDeploymentConfig(Map deploymentConfig, List templates){
        println 'Preparing Deployment Templates ...'
        List errors=[]

        templates.each { Map template ->
            println "Preparing ${template.file}"
            template.objects.each { Map object ->
                println "Preparing ${key(object)}  (${object.metadata.namespace})"
                object.metadata.labels['app-name'] = config.app.name
                if (!'true'.equalsIgnoreCase(object.metadata.labels['shared'])){
                    object.metadata.labels['env-name'] = deploymentConfig.name
                    object.metadata.labels['app'] =  object.metadata.labels['app-name'] + '-' + object.metadata.labels['env-name']
                }
                String asCopyOf = object.metadata.annotations['as-copy-of']

                if ((object.kind == 'Secret' || object.kind == 'ConfigMap') &&  asCopyOf!=null){
                    Map sourceObject = ocGet([object.kind, asCopyOf,'--ignore-not-found=true',  '-n', object.metadata.namespace])
                    if (sourceObject ==  null){
                        errors.add("'${object.kind}/${asCopyOf}' was not found in '${object.metadata.namespace}'")
                    }else{
                        object.remove('stringData')
                        object.data=sourceObject.data
                    }
                }else if (object.kind == 'ImageStream'){
                    //retrieve image from the tools project
                    String buildImageStreamTagName = "${object.metadata.name}:${config.app.build.name}"
                    String deploymageStreamTagName = "${object.metadata.name}:${deploymentConfig.name}"
                    Map buildImageStreamTag = ocGet(['ImageStreamTag', "${buildImageStreamTagName}",'--ignore-not-found=true',  '-n', config.app.build.namespace])
                    Map deployImageStreamTag = ocGet(['ImageStreamTag', "${deploymageStreamTagName}",'--ignore-not-found=true',  '-n', object.metadata.namespace])
                    if (deployImageStreamTag == null){
                        //Creating ImageStreamTag
                        oc(['tag', "${config.app.build.namespace}/${buildImageStreamTagName}", "${object.metadata.namespace}/${deploymageStreamTagName}", '-n', object.metadata.namespace])
                    }else if (buildImageStreamTag.image.metadata.name !=  deployImageStreamTag.image.metadata.name ){
                        //Updating ImageStreamTag
                        oc(['tag', "${config.app.build.namespace}/${buildImageStreamTagName}", "${object.metadata.namespace}/${deploymageStreamTagName}", '-n', object.metadata.namespace])
                    }
                    //println "${buildImageStreamTag}"
                    //oc(['cancel-build', "bc/${object.metadata.name}", '-n', object.metadata.namespace])
                }else if (object.kind == 'DeploymentConfig'){
                    //The DeploymentConfig.spec.template.spec.containers[].image cannot be empty when updating
                    Map currentDeploymentConfig = ocGet(['DeploymentConfig', "${object.metadata.name}",'--ignore-not-found=true',  '-n', "${object.metadata.namespace}"])

                    //Preserve current number of replicas
                    if (currentDeploymentConfig){
                        object.spec.replicas=currentDeploymentConfig.spec.replicas
                    }

                    Map containers =[:]
                    for (Map container:object.spec.template.spec.containers){
                        containers[container.name]=container
                    }

                    for (Map trigger:object.spec.triggers){
                        if (trigger.type == 'ImageChange'){
                            trigger.imageChangeParams.from.namespace = trigger.imageChangeParams.from.namespace?:object.metadata.namespace
                            Map imageStreamTag = ocGet(['ImageStreamTag', "${trigger.imageChangeParams.from.name}",'--ignore-not-found=true',  '-n', "${trigger.imageChangeParams.from.namespace}"])
                            for (String targetContainerName:trigger.imageChangeParams.containerNames){
                                containers[targetContainerName].image=imageStreamTag.image.dockerImageReference
                            }
                        }
                    }

                }
            }
        }

        if (errors.size()){
            throw new RuntimeException("The following errors were found: ${errors.join(';')}")
        }

        templates.each { Map template ->
            println "Applying ${template.file}"
            Map ret= ocApply(template.objects, ['-n', deploymentConfig.namespace, '--force=true'])
            if (ret.status != 0) {
                println ret
                System.exit(ret.status)
            }
        }

    } //end applyBuildConfig

    public void deploy(){
        List templates = loadDeploymentTemplates()
        applyDeploymentConfig(config.app.deployment, templates)
    }
}