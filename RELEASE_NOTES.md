### 2021a rev. 3

Like 2021a rev.2 this release is also different from regular updates. It is
closely correlated with IANA's `2021d` and `2021e`. See below for more details.

TZDB version `2021c` reverted most (but not all) time zone merges from `2021b`.
As Android has not applied any of them and there were no other rules update,
it is a no-op for Android.

Then TZDB version `2021d` was released with Fiji changes. That corresponds to
ICU's `2021a2`. But `2021a2` also picked up a
`"Link America/Panama America/Coral_Harbour"` change from IANA's TZDB release
in the backward file.

ICU's `2021a3` is `2021a2` with Palestine changes. No extra change there.

`tzdata2021a.tar.gz` in this Android TZDB update is ICU's [`tzdata2021a3.tar.gz`](https://github.com/unicode-org/icu-data/blob/d90a4eed92e3c5221c4dc1977bfdb7c072a8bb3d/tzdata/tzdata_patch/tzdata2021a3.tar.gz)
with `Link` change mention above reverted. Not reverting it will put
`America/Coral_Harbour` under Panama in `tzlookup.xml`. Right approach here
is to update tooling, but that will take some time.


### 2021a rev. 2

In `2021b` several time zones which were alike since 1970 were merged. It
received backlash in tz mailing-list. Please check
[September archive](https://mm.icann.org/pipermail/tz/2021-September/thread.html)
for discussions around the merges.

This contains a subset of the updates from IANA's 2021b update, but not all
because they cause problems for various libraries and OSes, Android included.
New links cause problems for the tzlookup.xml generation process, which have
yet to be resolved. Android API behavior is influenced by ICU, so while ICU
are forked and until things become clearer, so is Android.

ICU decided to skip zone merge changes and named their release `2021a1`.
Unfortunately there are hardcoded places in Android which expect tzdb version
to be exactly 5 characters and we release it as `2021a rev. 2`.

`input_data/iana/tzdata2021a.tar.gz` is renamed ICU's `2021a1.tar.gz`. As it
is not signed, `tzdata2021a.tar.gz.asc` was deleted for this release only.

`2021a1` contains the following changes applied on top of `2021a`:
```
# Portugal observed DST in 1950
https://github.com/eggert/tz/commit/ed2f9d6b01d1256a4d463d2ceb953e15e1673d72
# Fix Guyana LMT and later historical transitions
https://github.com/eggert/tz/commit/0252f09cdff3769ecbf6adfdaf6c47055bbfb74b
# Add Barbados DST 1942-1944, fix end of LMT
https://github.com/eggert/tz/commit/42efb563b8224a20c1a6480b38427e5b4634da36
# Backzone fixes for Gambia, Malawi, Sierra Leone
https://github.com/eggert/tz/commit/bdb47c5fd68996c04a69d6b23c4c4a5a81df5c95
# Sierra Leone did not use DST 1957-1962
https://github.com/eggert/tz/commit/6982e34ab92cf13f3f3fb20212aca8aa2e4f0aae
# Tonga moved from +12:20 to +13 in 1961, not 1941
https://github.com/eggert/tz/commit/666022ef2a65bb9872ffa6d98cb73236c5f6daa0
# Tonga switched to standard time in 1945, not 1901
https://github.com/eggert/tz/commit/03cc98977ea7c93c49ecb58eae07cd01e9034bfd
# Cook Islands had two Christmases in 1899
https://github.com/eggert/tz/commit/bf12c3ba8513b640b83c245f804c37feeccb862b
# More pre-1978 fixes for Cook and Niue
https://github.com/eggert/tz/commit/a10451bb3fa7f84181f29ac81b06008fd1d81249
# Fix north Vietnam lat+long
https://github.com/eggert/tz/commit/6860c875400b70be52c71d7b7b9642b8667a8112
# Niue left -11:20 in 1964, not 1978
https://github.com/eggert/tz/commit/b73f9fd0364e1795b40cd325661ce609d6126377
# Samoa no longer observes DST
https://github.com/eggert/tz/commit/29583c461a9705118560e02e69bb64a0bf2fce0c
# Jordan now starts DST on February’s last Thursday.
https://github.com/eggert/tz/commit/39df8c8b22605f59f71213cfb92b3fd321e31d3c
```

These changes are in chronological order -- from old to new, so apply them
accordingly.

"Replace Pacific/Enderbury with Pacific/Kanton" change was skipped as it
requires changes in CLDR and `tzdb2021b` was announced late in CLDR's release
cycle.

Code changes were skipped as Android uses fixed version of tzcode, not ToT.


