% A very simple script to show how to generate a capture sequence JSON to
% be read by devCam. A sequence is a MATLAB vector-of-structs, each with
% the appropriate fields, below. This gets turned into a JSON array of
% objects using the savejson function.
%
% Note that you may want to refer programatically to your device's
% capability bounds, as found in the generated cameraReport.json for your
% device, so as to set meaningful parameters here.
%
% The five parameterizable values should be only the struct fields, though
% note that capitalization is irrelevant:
% .exposureTime
% .aperture
% .sensitivity  (this is ISO, but we will be consistent with metadata name)
% .focalLength
% .focalDistance
%
% Rob Sumner, March 2015


% This example sweeps through the exposure times available for the Nexus 5
% (which is where the low- and high-end values come from) in order to
% create a rudimentary exposure-bracketing set of captures.
minExpTime = 13231;
maxExpTime = 866975130;


out = []; % initialize output array so we can cat to it each
for i = round(logspace(log10(minExpTime),log10(maxExpTime),10))
    temp = struct('exposuretime',i,...    % units: ns
        'aperture',2.4,...      % units: f-stop. Often fixed.
        'sensitivity',100,...   % units: ISO
        'focallength',1,...     % units: mm. Often fixed.
        'focaldistance',9);     % units: diopters (possible uncalibrated)
    out = cat(2,out,temp);
end

% savejson form looks like this, with the empty string as first argument
savejson('',out,'exposureTime_sweep.json');