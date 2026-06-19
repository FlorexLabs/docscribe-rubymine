#!/usr/bin/env ruby
# frozen_string_literal: true

require 'json'
require 'stringio'
require 'docscribe'
require 'docscribe/cli'

$stdin.sync = true
$stdout.sync = true

def build_args(req, file)
  args = []
  case req['command']
  when 'check'
    args << '--format' << 'json' if req['format_json']
  when 'safe_fix'
    args << '-a'
    args << '-B' if req['no_boilerplate']
  when 'aggressive_fix'
    args << '-A' << '-k'
    args << '-B' if req['no_boilerplate']
  when 'update_types'
    args << '--rbs-collection'
    args << '-A' << '-k' << '-B'
  end
  args << '--rbs-collection' if req['rbs']
  args << file if file
  args
end

def run_command(args)
  orig_stdout = $stdout
  orig_stderr = $stderr
  captured_out = StringIO.new
  captured_err = StringIO.new
  $stdout = captured_out
  $stderr = captured_err
  exit_code = 0
  begin
    options = Docscribe::CLI::Options.parse!(args)
    exit_code = Docscribe::CLI::Run.run(options: options, argv: args.select { |a| !a.start_with?('-') })
  rescue SystemExit => e
    exit_code = e.status
  rescue => e
    exit_code = 2
    captured_err.puts("Error: #{e.message}")
  end
  $stdout = orig_stdout
  $stderr = orig_stderr
  [exit_code, captured_out.string, captured_err.string]
end

$stdin.each_line do |line|
  line = line.strip
  next if line.empty?
  begin
    req = JSON.parse(line)
    cmd = req['command']
    if cmd == 'ping'
      puts(JSON.generate({ id: req['id'], exit_code: 0, stdout: 'pong', stderr: '' }))
      $stdout.flush
      next
    end
    if cmd == 'shutdown'
      puts(JSON.generate({ id: req['id'], exit_code: 0, stdout: '', stderr: '' }))
      $stdout.flush
      break
    end
    file = req['file']
    args = build_args(req, file)
    exit_code, stdout, stderr = run_command(args)
    puts(JSON.generate({ id: req['id'], exit_code: exit_code, stdout: stdout, stderr: stderr }))
    $stdout.flush
  rescue => e
    puts(JSON.generate({ id: req['id'], exit_code: 2, stdout: '', stderr: "Daemon error: #{e.message}" }))
    $stdout.flush
  end
end
