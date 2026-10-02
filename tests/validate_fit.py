#!/usr/bin/env python3
import sys, json
from garmin_fit_sdk import Decoder, Stream

path=sys.argv[1]
decoder=Decoder(Stream.from_file(path))
assert decoder.is_fit(), 'Not a FIT file'
assert decoder.check_integrity(), 'Garmin integrity validation failed'
messages,errors=Decoder(Stream.from_file(path)).read(convert_datetimes_to_dates=False,convert_types_to_strings=False)
assert not errors, errors
sessions=messages['session_mesgs']; laps=messages['lap_mesgs']; records=messages['record_mesgs']
assert len(sessions)==1 and len(laps)==1 and len(records)==44
session=sessions[0]
assert session['sport']==2 and session['sub_sport']==8
assert session['total_ascent']==20 and laps[0]['total_ascent']==20
assert session['total_distance']==129 and session['total_timer_time']==43
assert messages['file_id_mesgs'][0]['manufacturer']==1
assert messages['file_id_mesgs'][0]['product']==3289
assert all(b['timestamp']-a['timestamp']==1 for a,b in zip(records,records[1:]))
assert all(r['heart_rate']==130 for r in records)
alt=[r['enhanced_altitude'] for r in records]
raw_gain=sum(max(0,b-a) for a,b in zip(alt,alt[1:]))
assert raw_gain==120, ('The original elevation graph must retain all 120 m gain',raw_gain)
assert min(alt)==100 and max(alt)==210
print(json.dumps({'official_garmin_decoder':'PASS','records':len(records),'sessions':len(sessions),'file_ascent_m':session['total_ascent'],'unchanged_profile_gain_m':raw_gain,'lift_ascent_excluded_m':raw_gain-session['total_ascent'],'garmin_product':3289},indent=2))
