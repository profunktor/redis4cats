let GithubActions =
      https://raw.githubusercontent.com/gvolpe/github-actions-dhall/feature/scala-actions/package.dhall sha256:c9b9020c54478267364393db3f735974ec4b6eafa4c88057912f77dc9dcae7be

let matrix = toMap { java = [ "8.0.242", "11.0.5" ] }

let setup =
      [ GithubActions.steps.checkout
      , GithubActions.steps.run
          { run = "docker-compose up -d" }
      , GithubActions.steps.run
          { run =
              ''
              shasum build.sbt \
                project/plugins.sbt \
                project/build.properties \
                project/Dependencies.scala > gha.cache.tmp
              ''
          }
      , GithubActions.steps.cache
          { path = "~/.sbt", key = "sbt", hashFile = "gha.cache.tmp" }
      , GithubActions.steps.cache
          { path = "~/.cache/coursier"
          , key = "coursier"
          , hashFile = "gha.cache.tmp"
          }
      , GithubActions.steps.java-setup
          { java-version = "\${{ matrix.java}}" }
      , GithubActions.steps.run
          { run = "sbt buildRedis4Cats" }
      , GithubActions.steps.run
          { run = "docker-compose down" }
      ]

in  GithubActions.Workflow::{
    , name = "Scala"
    , on = GithubActions.On::{
      , push = Some GithubActions.Push::{
          branches = Some [ "master" ]
        }
      , pull_request = Some GithubActions.PullRequest::{=}
      }
    , jobs = toMap
        { build = GithubActions.Job::{
          , name = "Build"
          , needs = None (List Text)
          , strategy = Some GithubActions.Strategy::{ matrix = matrix }
          , runs-on = GithubActions.types.RunsOn.`ubuntu-18.04`
          , steps = setup
          }
        }
    }
