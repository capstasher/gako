project('gako-bridge', 'c',
  version: '0.1.0',
  license: 'GPL-3.0-or-later',
  default_options: ['c_std=gnu11', 'warning_level=2'])

target = get_option('target')
cc = meson.get_compiler('c')

deps = [
  dependency('gegl-0.4'),
  dependency('babl-0.1'),
  dependency('json-glib-1.0'),
  dependency('gobject-2.0'),
  dependency('glib-2.0'),
]

add_project_arguments('-Wno-unused-parameter', language: 'c')

if target.startswith('android')
  shared_library('gako',
    'gako_core.c', 'gako_modes.c', 'gako_jni.c',
    dependencies: deps + [cc.find_library('log'), cc.find_library('jnigraphics'), cc.find_library('m', required: false)],
    install: true)
else
  core = static_library('gako_core', 'gako_core.c', 'gako_modes.c', dependencies: deps)
  executable('gako-host-test', 'test_host.c',
    link_with: core,
    dependencies: deps,
    install: true)
endif
