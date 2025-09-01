{
  description = "ProfunKtor - Scala development tools";

  inputs.dev-tools.url = github:profunktor/dev-tools;

  outputs = { dev-tools, ... }: {
    inherit (dev-tools) devShells packages;
  };
}
