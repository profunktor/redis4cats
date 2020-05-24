let GithubActions =
      https://raw.githubusercontent.com/regadas/github-actions-dhall/master/package.dhall sha256:40602cb9f4e3d1964e87bc88385c7946d9796b0fb1358249fce439ac9f30c726

let setup =
      [ GithubActions.steps.checkout
      , GithubActions.steps.olafurpg/java-setup { java-version = "11" }
      , GithubActions.steps.olafurpg/gpg-setup
      , GithubActions.steps.olafurpg/sbt-ci-release
      ]

in  GithubActions.Workflow::{
    , name = "Release"
    , on = GithubActions.On::{
      , push = Some GithubActions.Push::{
        , branches = Some [ "master" ]
        , tags = Some [ "*" ]
        }
      }
    , jobs = toMap
        { build = GithubActions.Job::{
          , name = "Publish"
          , needs = None (List Text)
          , runs-on = GithubActions.types.RunsOn.`ubuntu-18.04`
          , steps = setup
          }
        }
    }
