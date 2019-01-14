(: Implements part of the functions from BEA ALSB, refer to https://docs.oracle.com/cd/E13171_01/alsb/docs25/userguide/appendixxquery.html :)
xquery version "3.0";

module namespace fn-bea='http://osb-legacy-support';

declare function fn-bea:uuid() as xs:string { fn-artofarc:uuid() };
declare function fn-bea:format-number($number as xs:double, $pattern as xs:string) as xs:string { fn:format-number($number, $pattern) };
declare function fn-bea:time-from-string-with-format($format as xs:string, $time as xs:time) as xs:string { fn:format-time($time, '[H,2][m,2][s,2]') };
declare function fn-bea:date-to-string-with-format($format as xs:string, $date as xs:date) as xs:string {
	switch ($format) 
		case "yyyy-MM-dd"	return fn:format-date($date, '[Y,4]-[M,2]-[D,2]')
		case "dd.MM.yyyy"	return fn:format-date($date, '[D,2].[M,2].[Y,4]')
		case "ddMMyyyy"		return fn:format-date($date, '[D,2][M,2][Y,4]')
		default				return "Unsupported date format"
};
declare function fn-bea:date-from-string-with-format($format as xs:string, $dateString as xs:string) as xs:date? {
	switch ($format) 
		case "dd.MM.yyy"	return xs:date(concat(substring($dateString,7),'-',substring($dateString,4,2),'-',substring($dateString,1,2)))
		case "ddMMyyyy"		return xs:date(concat(substring($dateString,5),'-',substring($dateString,3,2),'-',substring($dateString,1,2)))
		default				return ()
};
