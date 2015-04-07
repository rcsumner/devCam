function raw = dngRead(filename)
% raw =  dngRead()
% raw = dngRead(filename)
%
% Reads a DNG file created by devCam and returns the raw pixel CFA.
%
% If no filename is supplied, prompts the user to select a DNG file.
% 
% Requres MATLAB r2011a at least. Basically stolen from Steve Eddins.
% http://blogs.mathworks.com/steve/2011/03/08/tips-for-reading-a-camera-raw-file-into-matlab/
%
% Rob Sumner, March 2013

% Parse input
if nargin==0
    [fn, fp] = uigetfile('*.dng');
    filename = [fp fn];
end

% This works with the DNG output from a Nexus 5 on API 21, at least. I
% presume the DNG writing class in Android is the same across devices.
warning off MATLAB:tifflib:TIFFReadDirectory:libraryWarning
t = Tiff(filename,'r');
raw = read(t);
close(t);
meta_info = imfinfo(filename);
x_origin = 1;
width = meta_info.DefaultCropSize(1);
y_origin = 1;
height = meta_info.DefaultCropSize(2);
raw =double(raw(y_origin:y_origin+height-1,x_origin:x_origin+width-1));