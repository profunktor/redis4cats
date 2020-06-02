let GithubActions =
      https://raw.githubusercontent.com/regadas/github-actions-dhall/master/package.dhall sha256:40602cb9f4e3d1964e87bc88385c7946d9796b0fb1358249fce439ac9f30c726

let matrix = toMap { java = [ "8.0.242", "11.0.5" ] }

let setup =
      [ GithubActions.steps.checkout
      , GithubActions.steps.run { run = "docker-compose up -d" }
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
      , GithubActions.steps.olafurpg/java-setup
          { java-version = "\${{ matrix.java}}" }
      , GithubActions.steps.run { run = "sbt buildRedis4Cats" }
      , GithubActions.steps.run { run = "docker-compose down" }
      ]

in  GithubActions.Workflow::{
    , name = "Scala"
    , on = GithubActions.On::{
      , push = Some GithubActions.Push::{
        , branches = Some [ "master" ]
        , paths = Some [ "modules/**" ]
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
