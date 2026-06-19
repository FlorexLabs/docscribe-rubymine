#!/usr/bin/env ruby
# frozen_string_literal: true

$stdin.sync = true
$stdout.sync = true
$stderr.sync = true

require 'json'
require 'stringio'

require 'docscribe'
require 'docscribe/cli'

# ── helpers ────────────────────────────────────────────────────────────

def monotonic_now
  Process.clock_gettime(Process::CLOCK_MONOTONIC)
end

def daemon_debug(msg)
  return unless ENV['DOCSCRIBE_DAEMON_DEBUG'] == '1'
  STDERR.puts("[docscribe-daemon] #{msg}")
  STDERR.flush
end

# ── build_config cache ─────────────────────────────────────────────────
# Prevents Config.load → ConfigBuilder.build → load_plugins! on every
# request. Invalidated when config file or rbs_collection.lock.yaml changes.

class DocscribeDaemonConfigCache
  CACHE = {}

  def self.fetch(options)
    key = key_for(options)
    cached = CACHE[key]
    return cached if cached

    conf = yield
    CACHE[key] = conf
    conf
  end

  def self.key_for(options)
    cwd = Dir.pwd
    config_path = resolve_config_path(options[:config])
    config_mtime = safe_mtime(config_path)
    lock_mtime = safe_mtime(File.join(cwd, 'rbs_collection.lock.yaml'))

    relevant = {
      cwd: cwd,
      config_path: config_path,
      config_mtime: config_mtime,
      lock_mtime: lock_mtime,
      include: options[:include],
      exclude: options[:exclude],
      include_file: options[:include_file],
      exclude_file: options[:exclude_file],
      rbs: options[:rbs],
      rbs_collection: options[:rbs_collection],
      sig_dirs: options[:sig_dirs],
      sorbet: options[:sorbet],
      rbi_dirs: options[:rbi_dirs],
      keep_descriptions: options[:keep_descriptions],
      no_boilerplate: options[:no_boilerplate],
    }

    Marshal.dump(relevant)
  rescue
    "nocache:#{Dir.pwd}:#{$!.class}:#{$!.message}"
  end

  def self.resolve_config_path(explicit)
    return explicit if explicit && File.file?(explicit)
    return 'docscribe.yml' if File.file?('docscribe.yml')
    nil
  end

  def self.safe_mtime(path)
    return 0.0 if path.nil? || !File.file?(path)
    File.mtime(path).to_f
  rescue
    0.0
  end
end

module Docscribe
  module CLI
    module Run
      class << self
        unless method_defined?(:__docscribe_daemon_uncached_build_config)
          alias __docscribe_daemon_uncached_build_config build_config

          def build_config(options)
            DocscribeDaemonConfigCache.fetch(options) do
              t0 = monotonic_now
              conf = __docscribe_daemon_uncached_build_config(options)
              t1 = monotonic_now
              daemon_debug(format('build_config miss: %.1fms', (t1 - t0) * 1000.0))
              conf
            end
          end
        end
      end
    end
  end
end

# ── result cache for repeated check calls ──────────────────────────────
# IDE often fires the annotator several times on the same saved file.
# 2-second TTL avoids redundant analysis on identical file state.

class DocscribeDaemonResultCache
  CACHE = {}
  TTL_SECONDS = 2.0

  def self.get(key)
    entry = CACHE[key]
    return nil unless entry
    return nil if (monotonic_now - entry[:at]) > TTL_SECONDS
    entry
  end

  def self.put(key, exit_code:, stdout:, stderr:)
    CACHE[key] = { at: monotonic_now, exit_code: exit_code, stdout: stdout, stderr: stderr }
  end

  def self.key_for(req, file)
    return nil unless req['command'] == 'check'
    return nil unless file && File.file?(file)

    relevant = {
      command: req['command'],
      file: file,
      mtime: File.mtime(file).to_f,
      size: File.size(file),
      format_json: req['format_json'],
      rbs: req['rbs'],
      no_boilerplate: req['no_boilerplate'],
    }

    Marshal.dump(relevant)
  rescue
    nil
  end
end

# ── CLI argument builder ───────────────────────────────────────────────

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
    args << '-A' << '-k'
    args << '-B'
  end

  args << '--rbs-collection' if req['rbs']
  args << file if file
  args
end

# ── CLI runner (captures stdout/stderr) ────────────────────────────────

def run_command(args)
  orig_stdout = $stdout
  orig_stderr = $stderr

  captured_out = StringIO.new
  captured_err = StringIO.new

  $stdout = captured_out
  $stderr = captured_err

  exit_code = 0

  t0 = monotonic_now
  begin
    parse_t0 = monotonic_now
    options = Docscribe::CLI::Options.parse!(args)
    parse_t1 = monotonic_now

    run_t0 = monotonic_now
    exit_code = Docscribe::CLI::Run.run(options: options, argv: args)
    run_t1 = monotonic_now

    daemon_debug(format('parse=%.1fms run=%.1fms total=%.1fms',
                        (parse_t1 - parse_t0) * 1000.0,
                        (run_t1 - run_t0) * 1000.0,
                        (run_t1 - parse_t0) * 1000.0))
  rescue SystemExit => e
    exit_code = e.status
  rescue => e
    exit_code = 2
    captured_err.puts("Error: #{e.class}: #{e.message}")
  ensure
    $stdout = orig_stdout
    $stderr = orig_stderr
  end

  t1 = monotonic_now
  daemon_debug(format('run_command done: %.1fms (exit=%d)', (t1 - t0) * 1000.0, exit_code))

  [exit_code, captured_out.string, captured_err.string]
end

# ── main loop ──────────────────────────────────────────────────────────

while (line = $stdin.gets)
  line = line.strip
  next if line.empty?

  begin
    req = JSON.parse(line)
    id = req['id']
    cmd = req['command']

    case cmd
    when 'ping'
      puts(JSON.generate({ id: id, exit_code: 0, stdout: 'pong', stderr: '' }))
      $stdout.flush
      next

    when 'shutdown'
      puts(JSON.generate({ id: id, exit_code: 0, stdout: '', stderr: '' }))
      $stdout.flush
      break
    end

    file = req['file']

    # result cache hit?
    cache_key = DocscribeDaemonResultCache.key_for(req, file)
    if cache_key
      cached = DocscribeDaemonResultCache.get(cache_key)
      if cached
        daemon_debug("result cache hit: #{file}")
        puts(JSON.generate({
          id: id, exit_code: cached[:exit_code],
          stdout: cached[:stdout], stderr: cached[:stderr],
        }))
        $stdout.flush
        next
      end
    end

    args = build_args(req, file)
    exit_code, stdout, stderr = run_command(args)

    # populate result cache for check commands
    if cache_key && cmd == 'check' && exit_code < 2
      DocscribeDaemonResultCache.put(cache_key,
        exit_code: exit_code, stdout: stdout, stderr: stderr)
    end

    puts(JSON.generate({ id: id, exit_code: exit_code, stdout: stdout, stderr: stderr }))
    $stdout.flush
  rescue => e
    rid = (req && req['id']) || -1
    rfile = (req && req['file']) || nil
    $stderr.puts("[docscribe-daemon] #{cmd} error on #{rfile}: #{e.class}: #{e.message}")
    $stderr.puts(e.backtrace.first(5).join("\n  ")) if e.backtrace
    puts(JSON.generate({ id: rid, exit_code: 2, stdout: '', stderr: e.message }))
    $stdout.flush
  end
end
