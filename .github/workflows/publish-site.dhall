let GithubActions =
      https://raw.githubusercontent.com/gvolpe/github-actions-dhall/steps/cachix/package.dhall sha256:4cd8f64770d8b015c2fd52bae5ddfb5b393eb7e0936f7f8e18f311c591b888a5

let setup =
      [ GithubActions.steps.checkout
      , GithubActions.steps.cachix/install-nix
      , GithubActions.steps.cachix/cachix { cache-name = "scala-microsite" }
      , GithubActions.steps.run { run = "nix-shell run -- \"sbt publishSite\"" }
      ]

in  GithubActions.Workflow::{
    , name = "Microsite"
    , on = GithubActions.On::{
      , push = Some GithubActions.Push::{
        , branches = Some [ "master" ]
        , paths = Some [ "site/**" ]
        }
      }
    , jobs = toMap
        { publish = GithubActions.Job::{
          , name = "publish-site"
          , needs = None (List Text)
          , runs-on = GithubActions.types.RunsOn.`ubuntu-18.04`
          , steps = setup
          , env = Some (toMap { GITHUB_TOKEN = "\${{ secrets.GITHUB_TOKEN }}" })
          }
        }
    }
