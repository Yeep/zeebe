def gitBranch = 'master'
def jobName = 'dispatcher-benchmarks'

def buildScript =
"""\
#!/bin/bash -xe

mvn clean package
java -Dburst.size=10 -jar target/benchmarks.jar -bm sample -tu ns
"""
job(jobName)
{
    scm
    {
        git
        {
            remote
            {
                github 'camunda-tngp/dispatcher', 'ssh'
                credentials 'camunda-jenkins-github-ssh'
            }
            branch gitBranch
            extensions
            {
                localBranch gitBranch
            }
        }
    }
    label 'cam-int-1'
    jdk '(Default)'

    steps
    {
        shell buildScript
    }

    wrappers
    {
        timestamps()

        timeout
        {
            absolute 60
        }

    }

    publishers
    {
        extendedEmail
        {
            triggers
            {
                firstFailure
                {
                    sendTo
                    {
                       culprits()
                    }
                }
                fixed
                {
                    sendTo
                    {
                        culprits()
                    }
                }
            }
        }
    }

    logRotator(-1, 5, -1, 1)
}
