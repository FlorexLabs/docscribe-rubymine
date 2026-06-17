# frozen_string_literal: true

desc "Run tests"
task :test do
  sh "ruby -Ilib -Itest test/*_test.rb"
end

desc "Build gem"
task :build do
  sh "gem build docscribe.gemspec"
end
