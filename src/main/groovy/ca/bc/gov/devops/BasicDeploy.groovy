package ca.bc.gov.devops

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor

import java.nio.file.Paths

abstract class BasicDeploy extends Script {
    //abstract def runScript()
    CliBuilder cli
    OptionAccessor opt

    def runScript(URI scriptSourceUri) {
        File scriptSourceFile = Paths.get(scriptSourceUri).toFile()

        cli = new CliBuilder(usage: "groovy ${scriptSourceFile.getName()} --pr=<pull request#> --config=<path> --env=<name>")

        cli.with {
            h(longOpt: 'help', 'Show usage information')
            c(longOpt: 'config', args: 1, argName: 'Pipeline config file', 'Pipeline config file', required: true)
            e(longOpt: 'env', args: 1, argName: 'Target environment name', 'Target environment name', required: true)
            _(longOpt: 'pr', args: 1, argName: 'Pull Request Number', 'GitHub Pull Request #', required: true)
        }


        opt = cli.parse(args)


        if (opt == null) {
            //System.err << 'Error while parsing command-line options.\n'
            //cli.usage()
            System.exit 2
        }

        if (opt?.h) {
            cli.usage()
            return 0
        }

        println "Loading configuration file for '${opt.e}' ${opt.'pr'}"
        def configFile = new File(opt.c)

        def varsConfigSlurper = new ConfigSlurper(opt.e)
        varsConfigSlurper.setBinding(['opt': opt])

        def varsConfig = varsConfigSlurper.parse(new File(opt.c).toURI().toURL())

        def configSlurper = new ConfigSlurper(opt.e)
        configSlurper.setBinding(['opt': opt, 'vars': varsConfig.vars])

        def config = configSlurper.parse(new File(opt.c).toURI().toURL())
        config.opt = opt

        println config

        new OpenShiftDeploymentHelper(config).deploy()

    }
}